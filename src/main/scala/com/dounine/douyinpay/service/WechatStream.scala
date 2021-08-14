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
import com.dounine.douyinpay.router.routers.errors.LockedException
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.{DingDing, Request}
import org.slf4j.LoggerFactory
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

import java.net.URLEncoder
import java.time.{Clock, LocalDateTime}
import java.time.format.DateTimeFormatter
import scala.concurrent.Future
import scala.util.Try
import scala.concurrent.duration._

object WechatStream extends JsonParse with SuportRouter {

  private val logger = LoggerFactory.getLogger(WechatStream.getClass)

  def userInfoQuery()(implicit
      system: ActorSystem[_]
  ): Flow[String, WechatModel.WechatUserInfo, NotUsed] =
    Flow[String]
      .flatMapConcat { openid =>
        accessToken().map((openid, _))
      }
      .mapAsync(1) { tp2 =>
        val openid: String = tp2._1
        val token: Token = tp2._2
        Request
          .get[WechatModel.WechatUserInfo](
            s"https://api.weixin.qq.com/cgi-bin/user/info?access_token=${token}&openid=${openid}&lang=zh_CN"
          )
      }

  def userInfoQuery2()(implicit
      system: ActorSystem[_]
  ): Flow[
    (OrderModel.Recharge, String),
    (OrderModel.Recharge, WechatModel.WechatUserInfo),
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    Flow[(OrderModel.Recharge, String)]
      .flatMapConcat { tp2 =>
        accessToken().map((tp2, _))
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

  def accessToken()(implicit
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
            case AccessTokenBehavior.GetTokenOk(token) => token
            case AccessTokenBehavior.GetTokenFail(msg) =>
              throw new Exception(msg)
          }
      )
    })
  }

  def jsapiQuery()(implicit system: ActorSystem[_]): Source[String, NotUsed] = {
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
            case JSApiTicketBehavior.GetTicketOk(token) => token
            case JSApiTicketBehavior.GetTicketFail(msg) =>
              throw new Exception(msg)
          }
      )
    })
  }

  def menuCreate()(implicit
      system: ActorSystem[_]
  ): Flow[String, String, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    Flow[String]
      .zip(accessToken())
      .mapAsync(1) { result =>
        {
          val menu = result._1
          val accessToken = result._2
          Request.post[String](
            s"https://api.weixin.qq.com/cgi-bin/menu/create?access_token=${accessToken}",
            menu
          )
        }
      }
  }

  def notifyMessage()(implicit
      system: ActorSystem[_]
  ): Flow[WechatModel.WechatMessage, HttpResponse, NotUsed] = {
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val domain = system.settings.config.getString("app.file.domain")
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
                      "Content" -> s"\uD83C\uDF89点击左下角菜单抖币充值\uD83C\uDF89\n"
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
              xmlResponse(
                Map(
                  "ToUserName" -> message.fromUserName,
                  "FromUserName" -> message.toUserName,
                  "CreateTime" -> System.currentTimeMillis() / 1000,
                  "MsgType" -> "text",
                  "Content" -> "\uD83C\uDF89欢迎使用抖音直充、点击左下角菜单抖币充值快速充值\uD83C\uDF89"
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

  def jwtEncode(
      text: String
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
            openid = text
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

//  def webUserInfo()(implicit
//      system: ActorSystem[_]
//  ): Flow[Code, RouterModel.Data, NotUsed] = {
//    implicit val ec = system.executionContext
//    implicit val materializer = SystemMaterializer(system).materializer
//    val config = system.settings.config.getConfig("app")
//    val appid = config.getString("wechat.appid")
//    val secret = config.getString("wechat.secret")
//    val domain = config.getString("file.domain")
//    Flow[Code]
//      .mapAsync(1) { code =>
//        Request
//          .get[WechatModel.AccessTokenBase](
//            s"https://api.weixin.qq.com/sns/oauth2/access_token?appid=${appid}&secret=${secret}&code=${code}&grant_type=authorization_code"
//          )
//          .flatMap { result =>
//            if (result.errmsg.isDefined) {
//              Future.successful(
//                RouterModel.Data(
//                  status = ResponseCode.fail,
//                  msg = Some("认证失效、重新登录"),
//                  data = Some(
//                    Map(
//                      "redirect" -> s"https://open.weixin.qq.com/connect/oauth2/authorize?appid=${appid}&redirect_uri=${domain}&response_type=code&scope=snsapi_base&state=${appid}#wechat_redirect"
//                    )
//                  )
//                )
//              )
//            } else {
//              logger.info(
//                "openid -> {} access_token -> {}",
//                result.openid.get,
//                result.access_token.get
//              )
//              Request
//                .get[
//                  WechatModel.SNSUserInfo
//                ](
//                  s"https://api.weixin.qq.com/sns/userinfo?access_token=${result.access_token.get}&openid=${result.openid.get}&lang=zh_CN"
//                )
//                .map(result => {
//                  val (token, expire) = jwtEncode(result.openid)
//                  RouterModel.Data(
//                    Some(
//                      Map(
//                        "open_id" -> result.openid,
//                        "token" -> token,
//                        "expire" -> expire
//                      )
//                    )
//                  )
//                })
//            }
//          }
//      }
//  }

  def webBaseUserInfo()(implicit
      system: ActorSystem[_]
  ): Flow[
    LoginParamers,
    RouterModel.JsonData,
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer
    val config = system.settings.config.getConfig("app")
    val appid = config.getString("wechat.appid")
    val secret = config.getString("wechat.secret")
    val domain = config.getString("file.domain")
    val limitMoney = config.getInt("limitMoney")
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
            Request
              .get[WechatModel.AccessTokenBase](
                s"https://api.weixin.qq.com/sns/oauth2/access_token?appid=${appid}&secret=${secret}&code=${code}&grant_type=authorization_code"
              )
              .map(_ -> paramers)
        }
      })
      .flatMapConcat(result => {
        if (result._1.errmsg.isDefined) {
          val paramers = result._2
          val domainEncode = URLEncoder.encode(
            domain + s"?ccode=${paramers.ccode}",
            "utf-8"
          )
          Source.single(
            RouterModel.Data(
              status = ResponseCode.fail,
              msg = Some("认证失效、重新登录"),
              data = Some(
                Map(
                  "redirect" -> s"https://open.weixin.qq.com/connect/oauth2/authorize?appid=${appid}&redirect_uri=${domainEncode}&response_type=code&scope=snsapi_base&state=${appid}#wechat_redirect"
                )
              )
            )
          )
        } else {
          val paramers = result._2
          val openid = result._1.openid.get
          Source
            .single(openid)
            .via(AccountStream.queryAccount())
            .zip(
              Source
                .single(openid)
                .via(OrderStream.queryPaySum())
                .zipWith(
                  Source
                    .single(
                      OpenidModel.OpenidInfo(
                        openid = openid,
                        ccode = paramers.ccode,
                        ip = paramers.ip,
                        locked = false,
                        createTime = LocalDateTime.now()
                      )
                    )
                    .via(OpenidStream.autoCreateOpenidInfo())
                ) { (sum, _) => sum }
            )
            .map {
              case (
                    accountInfo: Option[AccountModel.AccountInfo],
                    paySum: Option[Int]
                  ) => {
                val (token, expire) = jwtEncode(openid)
                val enought =
                  (if (accountInfo.isEmpty)
                     paySum.getOrElse(0) < limitMoney
                   else true)
                if (!enought) {
                  logger.info(
                    "{} -> {} 需要收费",
                    openid,
                    paySum.getOrElse(0)
                  )
                }
                RouterModel.Data(
                  Some(
                    Map(
                      "open_id" -> openid,
                      "token" -> token,
                      "expire" -> expire,
//                          "volumn" -> accountInfo,
                      "enought" -> true, //enought,
                      "admin" -> admins.contains(openid)
                    )
                  )
                )
              }
            }
        }
      })
  }

  def webBaseUserInfo2()(implicit
      system: ActorSystem[_]
  ): Flow[
    LoginParamers,
    WechatModel.WechatLoginResponse,
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer
    val config = system.settings.config.getConfig("app")
    val appid = config.getString("wechat.appid")
    val secret = config.getString("wechat.secret")
    val domain = config.getString("file.domain")
    val limitMoney = config.getInt("limitMoney")
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
            Request
              .get[WechatModel.AccessTokenBase](
                s"https://api.weixin.qq.com/sns/oauth2/access_token?appid=${appid}&secret=${secret}&code=${code}&grant_type=authorization_code"
              )
              .map(_ -> paramers)
        }
      })
      .flatMapConcat(result => {
        if (result._1.errmsg.isDefined) {
          val paramers = result._2
          val domainEncode = URLEncoder.encode(
            domain + s"?ccode=${paramers.ccode}",
            "utf-8"
          )
          Source.single(
            WechatModel.WechatLoginResponse(
              redirect = Some(
                s"https://open.weixin.qq.com/connect/oauth2/authorize?appid=${appid}&redirect_uri=${domainEncode}&response_type=code&scope=snsapi_base&state=${appid}#wechat_redirect"
              )
            )
          )
        } else {
          val paramers = result._2
          val openid = result._1.openid.get
          Source
            .single(openid)
            .via(AccountStream.queryAccount())
            .zip(
              Source
                .single(openid)
                .via(OrderStream.queryPaySum())
                .zipWith(
                  Source
                    .single(
                      OpenidModel.OpenidInfo(
                        openid = openid,
                        ccode = paramers.ccode,
                        ip = paramers.ip,
                        locked = false,
                        createTime = LocalDateTime.now()
                      )
                    )
                    .via(OpenidStream.autoCreateOpenidInfo())
                ) { (sum, _) => sum }
            )
            .map {
              case (
                    accountInfo: Option[AccountModel.AccountInfo],
                    paySum: Option[Int]
                  ) => {
                val (token, expire) = jwtEncode(openid)
                val enought =
                  (if (accountInfo.isEmpty)
                     paySum.getOrElse(0) < limitMoney
                   else true)
                if (!enought) {
                  logger.info(
                    "{} -> {} 需要收费",
                    openid,
                    paySum.getOrElse(0)
                  )
                }
                WechatModel.WechatLoginResponse(
                  open_id = Some(openid),
                  token = Some(token),
                  expire = Some(expire),
                  //                          "volumn" -> accountInfo,
                  enought = Some(true), //enought,
                  admin = Some(admins.contains(openid))
                )
              }
            }
        }
      })
  }

}
