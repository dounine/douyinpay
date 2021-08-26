package com.dounine.douyinpay.behaviors.engine

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.stream.{RestartSettings, SystemMaterializer}
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.util.ByteString
import com.dounine.douyinpay.behaviors.engine.AccessTokenBehavior.logger
import com.dounine.douyinpay.model.models.{BaseSerializer, TokenModel}
import com.dounine.douyinpay.service.WechatStream
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.Request
import org.slf4j.LoggerFactory

import java.io.{File, FileReader}
import java.time.LocalDateTime
import scala.concurrent.Future
import scala.util.{Failure, Success}
import scala.concurrent.duration._

object JSApiTicketBehavior extends JsonParse {

  private val logger = LoggerFactory.getLogger(JSApiTicketBehavior.getClass)

  val typeKey: EntityTypeKey[Event] =
    EntityTypeKey[Event]("JSApiTicket")

  sealed trait Event extends BaseSerializer
  type Ticket = String
  case class InitTicket(appid: String, secret: String) extends Event
  case class InitTicketOk(
      appid: String,
      secret: String,
      ticket: Ticket,
      expireTime: LocalDateTime
  ) extends Event
  case class InitTicketFail(appid: String, code: Int, msg: String) extends Event
  case class GetTicket()(val replyTo: ActorRef[Event]) extends Event
  case class GetTicketOk(ticket: Ticket, expireTime: LocalDateTime)
      extends Event
  case class GetTicketFail(msg: String) extends Event

  case class TicketResponse(
      errcode: Int,
      errmsg: String,
      ticket: Option[String],
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

        var ticket: Option[String] = None
        var expire: Option[LocalDateTime] = None
        Behaviors.withTimers[Event] { timers =>
          {
            Behaviors.receiveMessage {
              case r @ GetTicket() => {
                r.replyTo.tell(ticket match {
                  case Some(value) => GetTicketOk(value, expire.get)
                  case None        => GetTicketFail("tick not found")
                })
                Behaviors.same
              }
              case InitTicketOk(appid, secret, to, expireTime) => {
                logger.info("ticket refresh -> {} {} {}", appid, to, expireTime)
                ticket = Some(to)
                expire = Some(expireTime)
                timers.startSingleTimer(
                  InitTicket(appid, secret),
                  java.time.Duration
                    .between(LocalDateTime.now(), expireTime)
                    .getSeconds
                    .seconds
                )
                Behaviors.same
              }
              case InitTicketFail(appid, code, msg) => {
                logger.error("ticket query fail -> {} {} {}", appid, code, msg)
                Behaviors.same
              }
              case InitTicket(appid, secret) => {
                import better.files._
                val path = System.getProperty("user.home")
                val ticketFile = s"${path}/.douyin_ticket_${appid}".toFile
                val ticketOpt = if (ticketFile.exists()) {
                  Some(ticketFile.lines().mkString(""))
                } else {
                  None
                }

                val ticket = ticketOpt match {
                  case Some(ticket) => {
                    val ts = ticket.split(" ")
                    val expireTime = LocalDateTime.parse(ts(1))
                    val expire = java.time.Duration
                      .between(LocalDateTime.now(), expireTime)
                      .getSeconds
                    if (expire > 0) {
                      Some((ts.head, expireTime))
                    } else None
                  }
                  case None => None
                }

                ticket match {
                  case Some((ticket, expireTime)) =>
                    context.self.tell(
                      InitTicketOk(
                        appid,
                        secret,
                        ticket,
                        expireTime
                      )
                    )
                  case None =>
                    if (config.getBoolean(s"wechat.${appid}.proxy")) {
                      val tickUrl =
                        config.getString(s"wechat.${appid}.tickUrl")
                      logger.info("{} tick proxy get -> {}", appid, tickUrl)
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
                                Request.get[TokenModel.TickResponse](
                                  s"${tickUrl}/${appid}/${secret}"
                                )
                              )
                          })
                          .runWith(Sink.head)
                      ) {
                        case Failure(exception) =>
                          InitTicketFail(appid, -1, exception.getMessage)
                        case Success(value) =>
                          InitTicketOk(
                            appid,
                            secret,
                            value.data.tick,
                            value.data.expire
                          )
                      }
                    } else if (pro) {
                      context.pipeToSelf(
                        RestartSource
                          .onFailuresWithBackoff(
                            RestartSettings(
                              minBackoff = 1.seconds,
                              maxBackoff = 3.seconds,
                              randomFactor = 0.2
                            ).withMaxRestarts(3, 10.seconds)
                          )(() => {
                            WechatStream
                              .accessToken(appid)
                              .mapAsync(1) { token =>
                                {
                                  Request
                                    .get[TicketResponse](
                                      s"https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=${token}&type=jsapi"
                                    )
                                    .map(result => {
                                      if (result.ticket.isDefined) {
                                        ticketFile.write(
                                          s"${result.ticket.get} ${LocalDateTime.now().plusSeconds(result.expires_in.get)}"
                                        )
                                      }
                                      result
                                    })
                                }
                              }
                          })
                          .runWith(Sink.head)
                      ) {
                        case Failure(exception) =>
                          InitTicketFail(appid, -1, exception.getMessage)
                        case Success(value) =>
                          if (value.errcode != 0) {
                            InitTicketFail(appid, value.errcode, value.errmsg)
                          } else {
                            InitTicketOk(
                              appid,
                              secret,
                              value.ticket.get,
                              LocalDateTime
                                .now()
                                .plusSeconds(value.expires_in.get)
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
