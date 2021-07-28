package com.dounine.douyinpay.behaviors.engine

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.stream.{RestartSettings, SystemMaterializer}
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.util.ByteString
import com.dounine.douyinpay.model.models.BaseSerializer
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.json.JsonParse
import org.slf4j.LoggerFactory

import java.io.{File, FileReader}
import java.time.LocalDateTime
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.duration._
object AccessTokenBehavior extends JsonParse {

  private val logger = LoggerFactory.getLogger(AccessTokenBehavior.getClass)

  val typeKey: EntityTypeKey[Event] =
    EntityTypeKey[Event]("AccessToken")

  sealed trait Event extends BaseSerializer
  type Token = String
  case class InitToken() extends Event
  case class InitTokenOk(token: Token, expire: Int) extends Event
  case class InitTokenFail(code: Int, msg: String) extends Event
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
        val http = Http(system)
        val config = system.settings.config.getConfig("app")
        val appid = config.getString("wechat.appid")
        val secret = config.getString("wechat.secret")

        var token: Option[String] = None
        Behaviors.receiveMessage {
          case r @ GetToken() => {
            r.replyTo.tell(token match {
              case Some(value) => GetTokenOk(value)
              case None        => GetTokenFail("not found")
            })
            Behaviors.same
          }
          case InitTokenOk(to, expire) => {
            logger.info("token refresh -> {} {}", to, expire)
            token = Some(to)
            context.setReceiveTimeout((expire).seconds, InitToken())
            Behaviors.same
          }
          case InitTokenFail(code, msg) => {
            logger.error("token query fail -> {} {}", code, msg)
            Behaviors.same
          }
          case InitToken() => {
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
                if (
                  time.plusSeconds(expire).isAfter(LocalDateTime.now())
                ) {
                  Some((ts.head, time, expire))
                } else None
              }
              case None => None
            }

            token match {
              case Some((token, time, expire)) =>
                context.self.tell(
                  InitTokenOk(
                    token,
                    expire - java.time.Duration
                      .between(time, LocalDateTime.now())
                      .getSeconds
                      .toInt
                  )
                )
              case None =>
                context.pipeToSelf(
                  RestartSource
                    .onFailuresWithBackoff(
                      RestartSettings(
                        minBackoff = 1.seconds,
                        maxBackoff = 3.seconds,
                        randomFactor = 0.2
                      ).withMaxRestarts(3, 10.seconds)
                    )(() => {
                      Source.future(
                        http
                          .singleRequest(
                            HttpRequest(
                              method = HttpMethods.GET,
                              uri =
                                s"https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid=${appid}&secret=${secret}"
                            ),
                            settings = ConnectSettings.httpSettings(system)
                          )
                          .flatMap {
                            case HttpResponse(_, _, entity, _) => {
                              entity.dataBytes
                                .runFold(ByteString.empty)(_ ++ _)
                                .map(_.utf8String)
                                .map(_.jsonTo[TokenResponse])
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
                            }
                            case msg => {
                              Future.failed(new Exception("请求失败"))
                            }
                          }
                      )
                    })
                    .runWith(Sink.head)
                ) {
                  case Failure(exception) =>
                    InitTokenFail(-1, exception.getMessage)
                  case Success(value) =>
                    value.errcode match {
                      case Some(err) =>
                        InitTokenFail(err, value.errmsg.getOrElse(""))
                      case None =>
                        InitTokenOk(
                          value.access_token.get,
                          value.expires_in.get
                        )
                    }

                }
            }

            Behaviors.same
          }

        }
      }
    }
}
