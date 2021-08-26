package com.dounine.douyinpay.behaviors.engine

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.stream.{RestartSettings, SystemMaterializer}
import com.dounine.douyinpay.model.models.BaseSerializer
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.Request
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.util.{Failure, Success}
object AccessTokenBehavior extends JsonParse {

  private val logger = LoggerFactory.getLogger(AccessTokenBehavior.getClass)

  val typeKey: EntityTypeKey[Event] =
    EntityTypeKey[Event]("AccessToken")

  sealed trait Event extends BaseSerializer
  type Token = String
  case class InitToken(appid: String, secret: String) extends Event
  case class InitTokenOk(
      appid: String,
      secret: String,
      token: Token,
      expire: Int
  ) extends Event
  case class InitTokenFail(
      appid: String,
      secret: String,
      code: Int,
      msg: String
  ) extends Event
  case class GetToken()(val replyTo: ActorRef[Event]) extends Event
  case class GetTokenOk(token: Token) extends Event
  case class GetTokenFail(msg: String) extends Event

  case class TokenResponse(
      errcode: Option[Int],
      errmsg: Option[String],
      access_token: Option[String],
      expires_in: Option[Int]
  ) extends BaseSerializer

  def apply(): Behavior[Event] =
    Behaviors.setup { context =>
      {
        implicit val system = context.system
        implicit val materializer = SystemMaterializer(system).materializer
        implicit val ec = context.executionContext
        val config = system.settings.config.getConfig("app")
        val pro = config.getBoolean("pro")

        var token: Option[String] = None
        Behaviors.withTimers[Event] { timers =>
          {
            Behaviors.receiveMessage {
              case r @ GetToken() => {
                r.replyTo.tell(token match {
                  case Some(value) => GetTokenOk(value)
                  case None        => GetTokenFail("token not found")
                })
                Behaviors.same
              }
              case InitTokenOk(appid, secret, to, expire) => {
                logger.info("token refresh -> {} {} {}", appid, to, expire)
                token = Some(to)
                if (pro) {
                  timers.startSingleTimer(
                    InitToken(appid, secret),
                    expire.seconds
                  )
                }
                Behaviors.same
              }
              case InitTokenFail(appid, secret, code, msg) => {
                logger.error("token query fail -> {} {} {}", appid, code, msg)
                Behaviors.same
              }
              case InitToken(appid, secret) => {
                val secret = config.getString(s"wechat.${appid}.secret")
                import better.files._
                val path = System.getProperty("user.home")
                val tokenFile = s"${path}/.douyin_token_${appid}".toFile
                val tokenOpt = if (tokenFile.exists()) {
                  Some(tokenFile.lines().mkString(""))
                } else {
                  None
                }

                val token = tokenOpt match {
                  case Some(token) => {
                    val ts = token.split(" ")
                    val time = LocalDateTime.parse(ts(1))
                    val expire = ts(2).toInt
                    if (time.plusSeconds(expire).isAfter(LocalDateTime.now())) {
                      Some((ts.head, time, expire))
                    } else None
                  }
                  case None => None
                }

                token match {
                  case Some((token, time, expire)) =>
                    context.self.tell(
                      InitTokenOk(
                        appid,
                        secret,
                        token,
                        expire - java.time.Duration
                          .between(time, LocalDateTime.now())
                          .getSeconds
                          .toInt
                      )
                    )
                  case None =>
                    if (pro) {
                      context.pipeToSelf(
                        RestartSource
                          .onFailuresWithBackoff(
                            RestartSettings(
                              minBackoff = 1.seconds,
                              maxBackoff = 3.seconds,
                              randomFactor = 0.2
                            ).withMaxRestarts(3, 10.seconds)
                          )(() => {
                            Source
                              .future(
                                Request.get[TokenResponse](
                                  s"https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${appid}&secret=${secret}"
                                )
                              )
                              .map(result => {
                                if (
                                  result.errcode.isEmpty && result.access_token.isDefined
                                ) {
                                  tokenFile.write(
                                    s"${result.access_token.get} ${LocalDateTime
                                      .now()} ${result.expires_in.get}"
                                  )
                                }
                                result
                              })
                          })
                          .runWith(Sink.head)
                      ) {
                        case Failure(exception) =>
                          InitTokenFail(appid, secret, -1, exception.getMessage)
                        case Success(value) =>
                          value.errcode match {
                            case Some(err) =>
                              InitTokenFail(
                                appid,
                                secret,
                                err,
                                value.errmsg.getOrElse("")
                              )
                            case None =>
                              InitTokenOk(
                                appid,
                                secret,
                                value.access_token.get,
                                value.expires_in.get
                              )
                          }

                      }
                    }
                }
                Behaviors.same
              }
            }
          }
        }
      }
    }
}
