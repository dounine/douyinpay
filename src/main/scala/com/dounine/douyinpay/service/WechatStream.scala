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
import akka.stream.scaladsl.{Flow, RestartSource, Sink, Source}
import akka.util.ByteString
import com.dounine.douyinpay.behaviors.engine.AccessTokenBehavior.Token
import com.dounine.douyinpay.behaviors.engine.{
  AccessTokenBehavior,
  JSApiTicketBehavior
}
import com.dounine.douyinpay.model.models.WechatModel.LoginParamers
import com.dounine.douyinpay.model.models.{
  AccountModel,
  OpenidModel,
  OrderModel,
  PayUserInfoModel,
  RouterModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.router.ResponseCode
import com.dounine.douyinpay.model.types.service.LogEventKey
import com.dounine.douyinpay.router.routers.SuportRouter
import com.dounine.douyinpay.router.routers.errors.{
  LockedException,
  ReLoginException
}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.{DingDing, OpenidPaySuccess, Request}
import org.slf4j.LoggerFactory
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

import java.net.{URLDecoder, URLEncoder}
import java.time.{Clock, LocalDateTime}
import java.time.format.DateTimeFormatter
import scala.concurrent.{Await, Future}
import scala.util.Try
import scala.concurrent.duration._

object WechatStream extends JsonParse with SuportRouter {

  private val logger = LoggerFactory.getLogger(WechatStream.getClass)

  def userInfoQuery(appid: String)(implicit
      system: ActorSystem[_]
  ): Flow[
    (OrderModel.Recharge, String),
    (OrderModel.Recharge, WechatModel.WechatUserInfo),
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    Flow[(OrderModel.Recharge, String)]
      .flatMapConcat { tp2 =>
        accessToken(appid).map((tp2, _))
      }
      .mapAsync(1) { tp2 =>
        val openid: String = tp2._1._2
        val token: Token = tp2._2
        Request
          .get[WechatModel.WechatUserInfo](
            s"https://api.weixin.qq.com/cgi-bin/user/info?access_token=${token}&openid=${openid}&lang=zh_CN"
          )
          .map(tp2._1._1 -> _)
      }
  }

  def userInfoQuery2(appid: String)(implicit
      system: ActorSystem[_]
  ): Flow[
    String,
    WechatModel.WechatUserInfo,
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    Flow[String]
      .flatMapConcat { openid =>
        accessToken(appid).map(openid -> _)
      }
      .mapAsync(1) { tp2 =>
        val token = tp2._2
        val openid = tp2._1
        Request
          .get[WechatModel.WechatUserInfo](
            s"https://api.weixin.qq.com/cgi-bin/user/info?access_token=${token}&openid=${openid}&lang=zh_CN"
          )
      }
  }

  def userInfoQuery3()(implicit
      system: ActorSystem[_]
  ): Flow[
    (String, String),
    WechatModel.WechatUserInfo,
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    Flow[(String, String)]
      .flatMapConcat { tp2 =>
        accessToken(tp2._1).map(tp2._2 -> _)
      }
      .mapAsync(1) { tp2 =>
        val token = tp2._2
        val openid = tp2._1
        Request
          .get[WechatModel.WechatUserInfo](
            s"https://api.weixin.qq.com/cgi-bin/user/info?access_token=${token}&openid=${openid}&lang=zh_CN"
          )
      }
  }

  def accessToken(appid: String)(implicit
      system: ActorSystem[_]
  ): Source[AccessTokenBehavior.Token, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val sharding = ClusterSharding(system)
    RestartSource.onFailuresWithBackoff(
      RestartSettings(
        minBackoff = 1.seconds,
        maxBackoff = 3.seconds,
        randomFactor = 0.5
      ).withMaxRestarts(3, 10.seconds)
    )(() => {
      Source.future(
        sharding
          .entityRefFor(
            AccessTokenBehavior.typeKey,
            appid
          )
          .ask(
            AccessTokenBehavior.GetToken()
          )(3.seconds)
          .map {
            case AccessTokenBehavior.GetTokenOk(token, expireTime) => token
            case AccessTokenBehavior.GetTokenFail(msg) =>
              throw new Exception(msg)
          }
      )
    })
  }

  def jsapiQuery(
      appid: String
  )(implicit system: ActorSystem[_]): Source[String, NotUsed] = {
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
            appid
          )
          .ask(
            JSApiTicketBehavior.GetTicket()
          )(3.seconds)
          .map {
            case JSApiTicketBehavior.GetTicketOk(token, expireTime) => token
            case JSApiTicketBehavior.GetTicketFail(msg) =>
              throw new Exception(msg)
          }
      )
    })
  }

//  def menuCreate()(implicit
//      system: ActorSystem[_]
//  ): Flow[String, String, NotUsed] = {
//    implicit val materializer = SystemMaterializer(system).materializer
//    implicit val ec = system.executionContext
//    Flow[String]
//      .zip(accessToken())
//      .mapAsync(1) { result =>
//        {
//          val menu = result._1
//          val accessToken = result._2
//          Request.post[String](
//            s"https://api.weixin.qq.com/cgi-bin/menu/create?access_token=${accessToken}",
//            menu
//          )
//        }
//      }
//  }

  def notifyMessage()(implicit
      system: ActorSystem[_]
  ): Flow[WechatModel.WechatMessage, HttpResponse, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val wechat = system.settings.config.getConfig("app.wechat")
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
                              | - appid: ${message.appid}
                              | - appname: ${wechat.getString(
                      s"${message.appid}.name"
                    )}
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
                      "Content" -> s"\uD83C\uDF89点击左下角菜单抖音充值\uD83C\uDF89\n"
                    )
                  )
                }
                case otherText => {
                  if (
                    Array("抖+", "火山", "快手", "虎牙").exists(otherText.contains)
                  ) {
                    xmlResponse(
                      Map(
                        "ToUserName" -> message.fromUserName,
                        "FromUserName" -> message.toUserName,
                        "CreateTime" -> System.currentTimeMillis() / 1000,
                        "MsgType" -> "text",
                        "Content" -> s"暂时不支持${otherText}充值噢、目前只支持抖音充值呢[凋谢][凋谢]"
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
                              | - appname: ${wechat.getString(
                      s"${message.appid}.name"
                    )}
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
                          | - appid: ${message.appid}
                          | - appname: ${wechat.getString(
                  s"${message.appid}.name"
                )}
                          | - 场景值: ${message.eventKey.getOrElse("")}
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
          } else if (
            message.eventKey.contains("DOUYIN_SGCC") || message.eventKey
              .contains("DOUYIN_YYCC")
          ) {
            val result = Await.result(
              Source
                .single(message.fromUserName)
                .via(
                  OrderStream.queryOpenidPaySum()
                )
                .runWith(Sink.head),
              3.seconds
            )
            if (result.getOrElse(0) > 100) {
              xmlResponse(
                Map(
                  "ToUserName" -> message.fromUserName,
                  "FromUserName" -> message.toUserName,
                  "CreateTime" -> message.createTime,
                  "MsgType" -> "text",
                  "Content" -> s"""请点击 👉 <a href="https://open.weixin.qq.com/connect/oauth2/authorize?appid=${message.appid}&redirect_uri=https%3A%2F%2Fdouyin.61week.com%3Fccode%3D${message.eventKey
                    .getOrElse(
                      ""
                    )}%26appid%3D${message.appid}%26platform%3Ddouyin%26bu%3Dhttps%3A%2F%2Fbackup.61week.com%2Fapi&response_type=code&scope=snsapi_base&state=${message.appid}&connect_redirect=1#wechat_redirect">充值链接</a>""".stripMargin
                )
              )
            } else {
              xmlResponse(
                Map(
                  "ToUserName" -> message.fromUserName,
                  "FromUserName" -> message.toUserName,
                  "CreateTime" -> message.createTime,
                  "MsgType" -> "text",
                  "Content" -> s"""请点击 👉 <a href="https://open.weixin.qq.com/connect/oauth2/authorize?appid=${message.appid}&redirect_uri=https%3A%2F%2Fdouyin.61week.com%2F%3Fccode%3D${message.eventKey
                    .getOrElse(
                      ""
                    )}%26platform%3Ddouyin%26appid%3D${message.appid}&response_type=code&scope=snsapi_base&state=${message.appid}&connect_redirect=1#wechat_redirect">充值链接</a>"""
                )
              )
            }
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
                          | - appid: ${message.appid}
                          | - appname: ${wechat.getString(
                  s"${message.appid}.name"
                )}
                          | - url：${URLDecoder
                  .decode(message.eventKey.getOrElse(""), "utf-8")}
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
              Source
                .single(
                  message.fromUserName
                )
                .via(
                  OpenidStream.query()
                )
                .runWith(Sink.head)
                .foreach((openidUser: Option[OpenidModel.OpenidInfo]) => {
                  DingDing.sendMessage(
                    DingDing.MessageType.fans,
                    data = DingDing.MessageData(
                      markdown = DingDing.Markdown(
                        title = s"新增关注",
                        text = s"""
                                  |## ${message.fromUserName}
                                  | - event: 新增关注
                                  | - appid: ${message.appid}
                                  | - appname: ${wechat.getString(
                          s"${message.appid}.name"
                        )}
                                  | - 场景值：${message.eventKey.getOrElse("")}
                                  | - 来源渠道：${openidUser
                          .map(_.ccode)
                          .getOrElse("无")}
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
                })
              xmlResponse(
                Map(
                  "ToUserName" -> message.fromUserName,
                  "FromUserName" -> message.toUserName,
                  "CreateTime" -> System.currentTimeMillis() / 1000,
                  "MsgType" -> "text",
                  "Content" -> "\uD83C\uDF89抖音充值、快手充值请点击下面的菜单\uD83C\uDF89"
                )
              )
            case "unsubscribe" =>
              DingDing.sendMessage(
                DingDing.MessageType.fans,
                data = DingDing.MessageData(
                  markdown = DingDing.Markdown(
                    title = s"取消关注",
                    text = s"""
                              |## ${message.fromUserName}
                              | - event: 取消关注
                              | - appid: ${message.appid}
                              | - appname: ${wechat.getString(
                      s"${message.appid}.name"
                    )}
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
                          | - appname: ${wechat.getString(
                  s"${message.appid}.name"
                )}
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

  def jwtEncode(
      appid: String,
      openid: String
  )(implicit system: ActorSystem[_]): (String, Long) = {
    val config = system.settings.config.getConfig("app")
    val jwtSecret = config.getString("jwt.secret")
    val jwtExpire = config.getDuration("jwt.expire").getSeconds
    val begin = System.currentTimeMillis() / 1000
    val jwt = Jwt.encode(
      JwtHeader(JwtAlgorithm.HS256),
      JwtClaim(
        WechatModel
          .Session(
            appid = appid,
            openid = openid
          )
          .toJson
      ).issuedAt(begin)
        .expiresIn(jwtExpire)(Clock.systemUTC),
      jwtSecret
    )
    (jwt, (begin + jwtExpire))
  }

  def jwtDecode(
      text: String
  )(implicit system: ActorSystem[_]): Option[WechatModel.Session] = {
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
      val session = result.get._2.jsonTo[WechatModel.Session]
      Some(session)
    } else None
  }

  def webBaseUserInfo()(implicit
      system: ActorSystem[_]
  ): Flow[
    LoginParamers,
    WechatModel.WechatLoginResponse,
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer
    val config = system.settings.config.getConfig("app")
    val admins = config.getStringList("admins")
    Flow[LoginParamers]
      .mapAsync(1)(paramers => {
        val code = paramers.code
        val tokenValid = paramers.token match {
          case Some(token) => jwtDecode(token)
          case None        => None
        }
        tokenValid match {
          case Some(session) =>
            Future.successful(
              (
                WechatModel.AccessTokenBase(
                  openid = Some(session.openid)
                ),
                paramers
              )
            )
          case None =>
            val appid = paramers.appid
            val secret = config.getString(s"wechat.${appid}.secret")
            Request
              .get[WechatModel.AccessTokenBase](
                s"https://api.weixin.qq.com/sns/oauth2/access_token?appid=${appid}&secret=${secret}&code=${code}&grant_type=authorization_code"
              )
              .map(_ -> paramers)
        }
      })
      .flatMapConcat(result => {
        if (result._1.errmsg.isDefined) {
          throw ReLoginException(
            result._1.errmsg.getOrElse(""),
            Some(result._2.appid)
          )
        } else {
          val paramers = result._2
          val openid = result._1.openid.get
          Source
            .single(openid)
            .via(WechatStream.userInfoQuery2(paramers.appid))
            .flatMapConcat(res => {
              Source
                .single(
                  OpenidModel.OpenidInfo(
                    openid = openid,
                    appid = result._2.appid,
                    ccode = result._2.ccode,
                    ip = result._2.ip,
                    locked = false,
                    createTime = LocalDateTime.now()
                  )
                )
                .via(OpenidStream.autoCreateOpenidInfo())
                .map(_ => res)
            })
            .map {
              case (
                    wechatUserInfo: WechatModel.WechatUserInfo
                  ) => {
//                val (token, expire) = paramers.token match {
//                  case Some(token) =>
//                    jwtDecode(token) match {
//                      case Some(value) => (paramers.token.get, value.exp.get)
//                      case None        => jwtEncode(paramers.appid, openid)
//                    }
//                  case None => jwtEncode(paramers.appid, openid)
//                }
                val (token, expire) = jwtEncode(paramers.appid, openid)
                WechatModel.WechatLoginResponse(
                  open_id = Some(openid),
                  token = Some(token),
                  expire = Some(expire),
                  admin = Some(admins.contains(openid)),
                  sub = Some(wechatUserInfo.subscribe == 1),
                  needSub =
                    if (
                      wechatUserInfo.nickname.isEmpty && OpenidPaySuccess
                        .query(openid)
                        .count >= 1
                    ) Some(true)
                    else Some(false),
                  subUrl =
                    if (
                      wechatUserInfo.nickname.isEmpty && OpenidPaySuccess
                        .query(openid)
                        .count >= 1
                    )
                      Some(config.getString(s"wechat.${paramers.appid}.subUrl"))
                    else None
                )
              }
            }
        }
      })
  }

}
