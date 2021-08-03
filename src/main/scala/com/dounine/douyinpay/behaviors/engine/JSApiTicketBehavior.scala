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
  case class InitTicket() extends Event
  case class InitTicketOk(ticket: Ticket, expire: Int) extends Event
  case class InitTicketFail(code: Int, msg: String) extends Event
  case class GetTicket()(val replyTo: ActorRef[Event]) extends Event
  case class GetTicketOk(ticket: Ticket) extends Event
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
        val appid = config.getString("wechat.appid")
        val pro = config.getBoolean("pro")

        var ticket: Option[String] = None
        Behaviors.withTimers[Event] { timers =>
          {
            Behaviors.receiveMessage {
              case r @ GetTicket() => {
                r.replyTo.tell(ticket match {
                  case Some(value) => GetTicketOk(value)
                  case None        => GetTicketFail("not found")
                })
                Behaviors.same
              }
              case InitTicketOk(to, expire) => {
                logger.info("ticket refresh -> {} {}", to, expire)
                ticket = Some(to)
                if (pro) {
                  timers.startSingleTimer(InitTicket(), expire.seconds)
                }
                Behaviors.same
              }
              case InitTicketFail(code, msg) => {
                logger.error("ticket query fail -> {} {}", code, msg)
                Behaviors.same
              }
              case InitTicket() => {
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
                    val time = LocalDateTime.parse(ts(1))
                    val expire = ts(2).toInt
                    if (time.plusSeconds(expire).isAfter(LocalDateTime.now())) {
                      Some((ts.head, time, expire))
                    } else None
                  }
                  case None => None
                }

                ticket match {
                  case Some((ticket, time, expire)) =>
                    context.self.tell(
                      InitTicketOk(
                        ticket,
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
                            WechatStream
                              .accessToken()
                              .mapAsync(1) { token =>
                                {
                                  Request
                                    .get[TicketResponse](
                                      s"https://api.weixin.qq.com/cgi-bin/ticket/getticket?access_token=${token}&type=jsapi"
                                    )
                                    .map(result => {
                                      if (result.ticket.isDefined) {
                                        ticketFile.write(
                                          s"${result.ticket.get} ${LocalDateTime
                                            .now()} ${result.expires_in.get}"
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
                          InitTicketFail(-1, exception.getMessage)
                        case Success(value) =>
                          if (value.errcode != 0) {
                            InitTicketFail(value.errcode, value.errmsg)
                          } else {
                            InitTicketOk(
                              value.ticket.get,
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
