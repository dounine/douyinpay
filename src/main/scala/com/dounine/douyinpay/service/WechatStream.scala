package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes
}
import akka.stream.{RestartSettings, SystemMaterializer}
import akka.stream.scaladsl.{Flow, RestartSource, Source}
import akka.util.ByteString
import com.dounine.douyinpay.behaviors.engine.{
  AccessTokenBehavior,
  JSApiTicketBehavior
}
import com.dounine.douyinpay.model.models.{
  PayUserInfoModel,
  RouterModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.router.ResponseCode
import com.dounine.douyinpay.router.routers.SuportRouter
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.DingDing
import org.slf4j.LoggerFactory
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

import java.time.{Clock, LocalDateTime}
import java.time.format.DateTimeFormatter
import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.duration._
object WechatStream extends JsonParse with SuportRouter {

  private val logger = LoggerFactory.getLogger(WechatStream.getClass)

  def userInfoQuery(implicit
      system: ActorSystem[_]
  ): Flow[String, WechatModel.WechatUserInfo, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val http = Http(system)

    Flow[String]
      .flatMapConcat { openid =>
        accessToken(system).map((openid, _))
      }
      .mapAsync(1) { tp2 =>
        {
          val openid = tp2._1
          val token = tp2._2
          http
            .singleRequest(
              request = HttpRequest(
                method = HttpMethods.GET,
                uri =
                  s"https://api.weixin.qq.com/cgi-bin/user/info?access_token=${token}&openid=${openid}&lang=zh_CN"
              )
            )
            .flatMap {
              case HttpResponse(
                    code,
                    value,
                    entity,
                    protocol
                  ) => {
                entity.dataBytes
                  .runFold(ByteString.empty)(_ ++ _)
                  .map(_.utf8String)
                  .map(result => {
                    logger.info(result)
                    result
                  })
                  .map(_.jsonTo[WechatModel.WechatUserInfo])
              }
              case msg =>
                Future.failed(new Exception("request fail"))
            }
        }
      }
  }

  def accessToken(implicit
      system: ActorSystem[_]
  ): Source[AccessTokenBehavior.Token, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val sharding = ClusterSharding(system)
    RestartSource.onFailuresWithBackoff(
      RestartSettings(
        minBackoff = 1.seconds,
        maxBackoff = 3.seconds,
        randomFactor = 0.2
      ).withMaxRestarts(3, 3.seconds)
    )(() => {
      Source.future(
        sharding
          .entityRefFor(
            AccessTokenBehavior.typeKey,
            AccessTokenBehavior.typeKey.name
          )
          .ask(
            AccessTokenBehavior.GetToken()
          )(3.seconds)
          .map {
            case AccessTokenBehavior.GetTokenOk(token) => {
              token
            }
            case AccessTokenBehavior.GetTokenFail(msg) =>
              throw new Exception(msg)
          }
      )
    })
  }

  def jsapiQuery(implicit system: ActorSystem[_]): Source[String, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val sharding = ClusterSharding(system)
    RestartSource.onFailuresWithBackoff(
      RestartSettings(
        minBackoff = 1.seconds,
        maxBackoff = 3.seconds,
        randomFactor = 0.2
      ).withMaxRestarts(3, 3.seconds)
    )(() => {
      Source.future(
        sharding
          .entityRefFor(
            JSApiTicketBehavior.typeKey,
            JSApiTicketBehavior.typeKey.name
          )
          .ask(
            JSApiTicketBehavior.GetTicket()
          )(3.seconds)
          .map {
            case JSApiTicketBehavior.GetTicketOk(token) => {
              token
            }
            case JSApiTicketBehavior.GetTicketFail(msg) =>
              throw new Exception(msg)
          }
      )
    })
  }

  def sendMessage(implicit
      system: ActorSystem[_]
  ): Flow[WechatModel.WechatMessage, WechatModel.WechatMessage, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val http = Http(system)
    val sharding = ClusterSharding(system)

    Flow[WechatModel.WechatMessage]
      .mapAsync(1) { message =>
        {
          if (message.event.contains("subscribe")) {
            sharding
              .entityRefFor(
                AccessTokenBehavior.typeKey,
                AccessTokenBehavior.typeKey.name
              )
              .ask(
                AccessTokenBehavior.GetToken()
              )(3.seconds)
              .flatMap {
                case AccessTokenBehavior.GetTokenOk(token) => {
                  http
                    .singleRequest(
                      request = HttpRequest(
                        method = HttpMethods.POST,
                        uri =
                          s"https://api.weixin.qq.com/cgi-bin/message/custom/send?access_token=${token}",
                        entity = HttpEntity(
                          contentType = MediaTypes.`application/json`,
                          string = Map(
                            "touser" -> message.fromUserName,
                            "msgtype" -> "text",
                            "text" -> Map(
                              "content" -> "\uD83C\uDF89欢迎使用音音冲冲、点击左下角菜单快速充值\uD83C\uDF89"
                            )
                          ).toJson
                        )
                      )
                    )
                    .flatMap {
                      case HttpResponse(
                            code,
                            value,
                            entity,
                            protocol
                          ) => {
                        entity.dataBytes
                          .runFold(ByteString.empty)(_ ++ _)
                          .map(_.utf8String)
                          .map(_ => message)
                      }
                      case msg =>
                        Future.failed(new Exception("request fail"))
                    }
                }
                case AccessTokenBehavior.GetTokenFail(msg) =>
                  Future.failed(new Exception("request fail"))
              }
          } else {
            Future.successful(message)
          }
        }
      }
  }

  def notifyMessage(implicit
      system: ActorSystem[_]
  ): Flow[WechatModel.WechatMessage, HttpResponse, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")

    Flow[WechatModel.WechatMessage]
      .map(message => {
        if (message.msgType != "event") {
          message.msgType match {
            case "text" =>
              DingDing.sendMessage(
                DingDing.MessageType.message,
                data = DingDing.MessageData(
                  markdown = DingDing.Markdown(
                    title = s"有新的消息",
                    text = s"""
                              |## ${message.fromUserName}
                              | - 消息：${message.content.getOrElse("")}
                              | - id：${message.msgId.getOrElse(0)}
                              | - time: ${LocalDateTime
                      .now()
                      .format(
                        timeFormatter
                      )}
                              | - ntime: ${LocalDateTime
                      .now()
                      .format(timeFormatter)}
                              |""".stripMargin
                  )
                ),
                system
              )
              message.content.getOrElse("") match {
                case "充值" => {
                  xmlResponse(
                    Map(
                      "ToUserName" -> message.fromUserName,
                      "FromUserName" -> message.toUserName,
                      "CreateTime" -> System.currentTimeMillis() / 1000,
                      "MsgType" -> "text",
                      "Content" -> "\uD83C\uDF89抖币充值链接\uD83C\uDF89\nhttps://recharge.kuaiyugo.com/api/v1/auth/auth_redirect?appid=wxc1a77335b1dd223a"
                    )
                  )
                }
                case otherText => {
                  if (Array("抖+", "火山", "快手").exists(otherText.contains)) {
                    xmlResponse(
                      Map(
                        "ToUserName" -> message.fromUserName,
                        "FromUserName" -> message.toUserName,
                        "CreateTime" -> System.currentTimeMillis() / 1000,
                        "MsgType" -> "text",
                        "Content" -> s"不支持${otherText}充值噢、只支持抖音充值呢[凋谢][凋谢]"
                      )
                    )
                  } else
                    xmlResponse(
                      Map(
                        "ToUserName" -> message.fromUserName,
                        "FromUserName" -> message.toUserName,
                        "CreateTime" -> System.currentTimeMillis() / 1000,
                        "MsgType" -> "transfer_customer_service"
                      )
                    )
                }
              }

            //image,voice,video,shortvideo,location,link
            case _ =>
              DingDing.sendMessage(
                DingDing.MessageType.message,
                data = DingDing.MessageData(
                  markdown = DingDing.Markdown(
                    title = s"有新的未知消息",
                    text = s"""
                              |## ${message.fromUserName}
                              |${message.toJson
                      .jsonTo[Map[String, Any]]
                      .map(i => s" - ${i._1}：${i._2}")
                      .mkString("\n")}
                              | - time: ${LocalDateTime
                      .now()
                      .format(
                        timeFormatter
                      )}
                              | - ntime: ${LocalDateTime
                      .now()
                      .format(timeFormatter)}
                              |""".stripMargin
                  )
                ),
                system
              )
              xmlResponse(
                Map(
                  "ToUserName" -> message.fromUserName,
                  "FromUserName" -> message.toUserName,
                  "CreateTime" -> message.createTime,
                  "MsgType" -> "transfer_customer_service"
                )
              )
          }
        } else if (message.event.contains("SCAN")) {
          DingDing.sendMessage(
            DingDing.MessageType.event,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = s"扫码登录事件",
                text = s"""
                          |## ${message.fromUserName}
                          | - 场景值：${message.eventKey.getOrElse("")}
                          | - time: ${LocalDateTime
                  .now()
                  .format(
                    timeFormatter
                  )}
                          | - ntime: ${LocalDateTime
                  .now()
                  .format(timeFormatter)}
                          |""".stripMargin
              )
            ),
            system
          )
          textResponse("success")
        } else if (message.event.contains("CLICK")) {
          if (message.eventKey.contains("CONCAT_SERVICE")) {
            xmlResponse(
              Map(
                "ToUserName" -> message.fromUserName,
                "FromUserName" -> message.toUserName,
                "CreateTime" -> message.createTime,
                "MsgType" -> "text",
                "Content" -> "直接回复具体问题就可以了噢、我们的客服会直接处理您的问题的。"
              )
            )
          } else {
            textResponse("success")
          }
        } else if (message.event.contains("VIEW")) {
          DingDing.sendMessage(
            DingDing.MessageType.event,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = s"点击链接事件",
                text = s"""
                          |## ${message.fromUserName}
                          | - url：${message.eventKey.getOrElse("")}
                          | - time: ${LocalDateTime
                  .now()
                  .format(
                    timeFormatter
                  )}
                          | - ntime: ${LocalDateTime
                  .now()
                  .format(timeFormatter)}
                          |""".stripMargin
              )
            ),
            system
          )
          textResponse("success")
        } else if (
          message.event.contains("subscribe") || message.event
            .contains("unsubscribe")
        ) {
          message.event.get match {
            case "subscribe" =>
              DingDing.sendMessage(
                DingDing.MessageType.fans,
                data = DingDing.MessageData(
                  markdown = DingDing.Markdown(
                    title = s"新增关注",
                    text = s"""
                              |## ${message.fromUserName}
                              | - 场景值：${message.eventKey.getOrElse("")}
                              | - time: ${LocalDateTime
                      .now()
                      .format(
                        timeFormatter
                      )}
                              | - ntime: ${LocalDateTime
                      .now()
                      .format(timeFormatter)}
                              |""".stripMargin
                  )
                ),
                system
              )
              textResponse("success")
            case "unsubscribe" =>
              DingDing.sendMessage(
                DingDing.MessageType.fans,
                data = DingDing.MessageData(
                  markdown = DingDing.Markdown(
                    title = s"取消关注",
                    text = s"""
                              |## ${message.fromUserName}
                              | - time: ${LocalDateTime
                      .now()
                      .format(
                        timeFormatter
                      )}
                              | - ntime: ${LocalDateTime
                      .now()
                      .format(timeFormatter)}
                              |""".stripMargin
                  )
                ),
                system
              )
              textResponse("success")
          }
        } else {
          DingDing.sendMessage(
            DingDing.MessageType.event,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = s"未知事件",
                text = s"""
                          |## ${message.fromUserName}
                          |${message.toJson
                  .jsonTo[Map[String, Any]]
                  .map(i => s" - ${i._1}：${i._2}")
                  .mkString("\n")}
                          | - time: ${LocalDateTime
                  .now()
                  .format(
                    timeFormatter
                  )}
                          | - ntime: ${LocalDateTime
                  .now()
                  .format(timeFormatter)}
                          |""".stripMargin
              )
            ),
            system
          )
          textResponse("success")
        }
      })
  }

  def jwtEncode(text: String)(implicit system: ActorSystem[_]): String = {
    val config = system.settings.config.getConfig("app")
    val jwtSecret = config.getString("jwt.secret")
    val jwtExpire = config.getDuration("jwt.expire").getSeconds
    println("encode", text)
    Jwt.encode(
      JwtHeader(JwtAlgorithm.HS256),
      JwtClaim(
        WechatModel
          .Session(
            openid = text
          )
          .toJson
      ).issuedAt(System.currentTimeMillis() / 1000)
        .expiresIn(jwtExpire)(Clock.systemUTC),
      jwtSecret
    )
  }

  def jwtDecode(
      text: String
  )(implicit system: ActorSystem[_]): Option[String] = {
    val config = system.settings.config.getConfig("app")
    val jwtSecret = config.getString("jwt.secret")
    if (Jwt.isValid(text, jwtSecret, Seq(JwtAlgorithm.HS256))) {
      val result: Try[(String, String, String)] = {
        Jwt.decodeRawAll(
          text.trim,
          jwtSecret,
          Seq(JwtAlgorithm.HS256)
        )
      }
      println("decode", result.get._2.jsonTo[WechatModel.Session].openid)
      Option(result.get._2.jsonTo[WechatModel.Session].openid)
    } else Option.empty
  }

  type Code = String
  type Openid = String
  def webBaseUserInfo(implicit
      system: ActorSystem[_]
  ): Flow[Code, RouterModel.Data, NotUsed] = {
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer
    val http = Http(system)
    val config = system.settings.config.getConfig("app")
    val appid = config.getString("wechat.appid")
    val secret = config.getString("wechat.secret")
    Flow[Code]
      .mapAsync(1) { code =>
        http
          .singleRequest(
            request = HttpRequest(
              method = HttpMethods.GET,
              uri =
                s"https://api.weixin.qq.com/sns/oauth2/access_token?appid=${appid}&secret=${secret}&code=${code}&grant_type=authorization_code"
            ),
            settings = ConnectSettings.httpSettings(system)
          )
          .flatMap {
            case HttpResponse(_, _, entity, _) =>
              entity.dataBytes
                .runFold(ByteString(""))(_ ++ _)
                .map(_.utf8String)
                .map(_.jsonTo[WechatModel.AccessTokenBase])
                .map(i => {
                  if (i.errmsg.isDefined) { //code已使用过或code过期
                    RouterModel.Data(
                      status = ResponseCode.fail,
                      msg = Some("认证失效、重新登录"),
                      data = Some(
                        Map(
                          "redirect" -> "https://open.weixin.qq.com/connect/oauth2/authorize?appid=wx7b168b095eb4090e&redirect_uri=https%3A%2F%2Fdypay2.61week.com&response_type=code&scope=snsapi_base&state=wx7b168b095eb4090e#wechat_redirect"
                        )
                      )
                    )
                  } else {
                    RouterModel.Data(
                      Some(
                        Map(
                          "open_id" -> i.openid.get,
                          "token" -> jwtEncode(i.openid.get)
                        )
                      )
                    )
                  }
                })
            case msg @ _ =>
              logger.error(s"请求失败 $msg")
              Future.failed(new Exception(s"请求失败 $msg"))
          }
      }
  }
}
