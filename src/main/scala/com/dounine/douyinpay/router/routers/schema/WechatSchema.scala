package com.dounine.douyinpay.router.routers.schema

import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{UserModel, WechatModel}
import com.dounine.douyinpay.model.types.service.LogEventKey
import com.dounine.douyinpay.router.routers.SecureContext
import com.dounine.douyinpay.router.routers.errors.{
  LockedException,
  ReLoginException
}
import com.dounine.douyinpay.router.routers.schema.SchemaDef.RequestInfo
import com.dounine.douyinpay.service.{AccountStream, OpenidStream, WechatStream}
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.{
  IpUtils,
  MD5Util,
  OpenidPaySuccess,
  UUIDUtil
}
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.LoggerFactory
import sangria.macros.derive.{
  AddFields,
  DocumentField,
  ExcludeFields,
  ObjectTypeDescription,
  ObjectTypeName,
  RenameField,
  deriveObjectType
}
import sangria.schema.{Argument, _}

import java.net.{URLDecoder, URLEncoder}
import java.util.UUID
import scala.concurrent.Future

object WechatSchema extends JsonParse {

  private val logger = LoggerFactory.getLogger(WechatSchema.getClass)

  val WechatLoginResponse =
    deriveObjectType[Unit, WechatModel.WechatLoginResponse](
      ObjectTypeName("WechatLoginResponse"),
      ObjectTypeDescription("登录响应"),
//      RenameField("",""),
//      ExcludeFields("", ""),
      DocumentField("redirect", "code失效重定向登录地扯"),
      DocumentField("open_id", "微信open_id"),
      DocumentField("token", "登录token"),
      DocumentField("enought", "余额是否足够"),
      DocumentField("expire", "token过期时间"),
      DocumentField("admin", "是否是管理员")
//      AddFields(
//        Field("reverse_name", OptionType(StringType), resolve = _.value.token)
//      )
    )

  val wechatLogin = Field[
    SecureContext,
    RequestInfo,
    WechatModel.WechatLoginResponse,
    WechatModel.WechatLoginResponse
  ](
    name = "wechatLogin",
    fieldType = WechatLoginResponse,
    description = Some("微信登录"),
    arguments = Argument(
      name = "appid",
      argumentType = StringType,
      description = "appid"
    ) :: Argument(
      name = "code",
      argumentType = StringType,
      description = "登录code"
    ) :: Argument(
      name = "ccode",
      argumentType = StringType,
      description = "来源渠道"
    ) :: Argument(
      name = "sign",
      argumentType = StringType,
      description = "加密"
    ) :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) =>
      Source
        .single(
          WechatModel.LoginParamers(
            code = c.arg[String]("code"),
            appid = c.arg[String]("appid"),
            ccode = c.arg[String]("ccode"),
            token = c.value.headers.get("token"),
            sign = c.arg[String]("sign"),
            ip = c.value.addressInfo.ip,
            scheme = c.value.scheme
          )
        )
        .map(i => {
          if (i.appid.trim == "") {
            throw ReLoginException(
              "appid is null set default",
              Some("wx7b168b095eb4090e")
            )
          } else if (
            !c.ctx.system.settings.config
              .getConfig("app.wechat")
              .hasPath(i.appid.trim)
          ) {
            throw ReLoginException(
              "appid not exit set default",
              Some("wx7b168b095eb4090e")
            )
          }
          if (
            MD5Util.md5(
              Array(
                i.appid,
                i.ccode,
                i.code,
                i.token.getOrElse("")
              ).sorted
                .mkString("")
            ) != i.sign
          ) {
            logger.error(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.wechatLoginSignError,
                  "appid" -> i.appid,
                  "ccode" -> i.ccode,
                  "code" -> i.code,
                  "token" -> i.token.getOrElse(""),
                  "sign" -> i.sign,
                  "ip" -> c.value.addressInfo.ip,
                  "province" -> c.value.addressInfo.province,
                  "city" -> c.value.addressInfo.city
                )
              ).toJson
            )
            throw ReLoginException("sign error")
          } else {
            logger.info(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.wechatLogin,
                  "appid" -> i.appid,
                  "ccode" -> i.ccode,
                  "token" -> i.token.getOrElse(""),
                  "ip" -> c.value.addressInfo.ip,
                  "province" -> c.value.addressInfo.province,
                  "city" -> c.value.addressInfo.city
                )
              ).toJson
            )
          }
          i
        })
        .flatMapConcat { i =>
          i.token match {
            case Some(token) =>
              WechatStream.jwtDecode(token)(c.ctx.system) match {
                case Some(session) =>
                  if (session.appid != i.appid) {
                    throw ReLoginException("appid not equals session appid")
                  }
                  Source
                    .single(session.openid)
                    .via(OpenidStream.query()(c.ctx.system))
                    .map(result => {
                      if (result.isDefined && result.get.locked) {
                        logger.error(
                          Map(
                            "time" -> System.currentTimeMillis(),
                            "data" -> Map(
                              "event" -> LogEventKey.userLockedAccess,
                              "appid" -> i.appid,
                              "openid" -> result.get.openid,
                              "ip" -> c.value.addressInfo.ip,
                              "province" -> c.value.addressInfo.province,
                              "city" -> c.value.addressInfo.city
                            )
                          ).toJson
                        )
                        throw LockedException(
                          "user locked -> " + result.get.openid
                        )
                      } else if (c.value.addressInfo.city == "济南") {
//                        if (OpenidPaySuccess.query(session.openid) <= 2) {
//                          logger.error(
//                            Map(
//                              "time" -> System.currentTimeMillis(),
//                              "data" -> Map(
//                                "event" -> LogEventKey.ipRangeLockedAccess,
//                                "appid" -> i.appid,
//                                "openid" -> result.get.openid,
//                                "ip" -> c.value.addressInfo.ip,
//                                "province" -> c.value.addressInfo.province,
//                                "city" -> c.value.addressInfo.city
//                              )
//                            ).toJson
//                          )
//                          throw LockedException(
//                            "ip locked -> " + result.get.openid
//                          )
//                        }
                      } else {
                        logger.info(
                          Map(
                            "time" -> System.currentTimeMillis(),
                            "data" -> Map(
                              "event" -> LogEventKey.wechatLogin,
                              "appid" -> i.appid,
                              "openid" -> session.openid,
                              "ccode" -> i.ccode,
                              "token" -> i.token.getOrElse(""),
                              "ip" -> c.value.addressInfo.ip,
                              "province" -> c.value.addressInfo.province,
                              "city" -> c.value.addressInfo.city
                            )
                          ).toJson
                        )
                      }
                      i
                    })
                case None =>
                  Source
                    .single(i)
                    .map(ii => {
                      logger.info(
                        Map(
                          "time" -> System.currentTimeMillis(),
                          "data" -> Map(
                            "event" -> LogEventKey.wechatLogin,
                            "appid" -> i.appid,
                            "ccode" -> i.ccode,
                            "token" -> i.token.getOrElse(""),
                            "ip" -> c.value.addressInfo.ip,
                            "province" -> c.value.addressInfo.province,
                            "city" -> c.value.addressInfo.city
                          )
                        ).toJson
                      )
                      ii
                    })
              }
            case None =>
              Source
                .single(i)
                .map(ii => {
                  logger.info(
                    Map(
                      "time" -> System.currentTimeMillis(),
                      "data" -> Map(
                        "event" -> LogEventKey.wechatLogin,
                        "appid" -> i.appid,
                        "ccode" -> i.ccode,
                        "token" -> i.token.getOrElse(""),
                        "ip" -> c.value.addressInfo.ip,
                        "province" -> c.value.addressInfo.province,
                        "city" -> c.value.addressInfo.city
                      )
                    ).toJson
                  )
                  ii
                })
          }
        }
        .via(WechatStream.webBaseUserInfo()(c.ctx.system))
        .recover {
          case e: ReLoginException =>
            e.printStackTrace()
            logger.error(e.getMessage)
            val appid = e.appid.getOrElse(c.arg[String]("appid"))
            val domain =
              c.ctx.system.settings.config.getString("app.file.domain")
            val domainEncode = URLEncoder.encode(
              (c.value.scheme + "://" + domain) + s"?ccode=${c
                .arg[String]("ccode")}&appid=${appid}",
              "utf-8"
            )
            WechatModel.WechatLoginResponse(
              redirect = Some(
                s"https://open.weixin.qq.com/connect/oauth2/authorize?appid=${appid}&redirect_uri=${domainEncode}&response_type=code&scope=snsapi_base&state=${appid}#wechat_redirect"
              )
            )
        }
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
  )

  val SignatureResponse =
    deriveObjectType[Unit, WechatModel.SignatureResponse](
      ObjectTypeName("SignatureResponse"),
      ObjectTypeDescription("授权"),
      DocumentField("appid", "公众号id"),
      DocumentField("nonceStr", "随机字符串"),
      DocumentField("timestamp", "时间"),
      DocumentField("signature", "签名")
    )

  val signature = Field[
    SecureContext,
    RequestInfo,
    WechatModel.SignatureResponse,
    WechatModel.SignatureResponse
  ](
    name = "signature",
    fieldType = SignatureResponse,
    description = Some("签名"),
    tags = Authorised :: Nil,
    arguments = Argument(
      name = "url",
      argumentType = StringType,
      description = "签名url"
    ) :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      logger.info(
        Map(
          "time" -> System.currentTimeMillis(),
          "data" -> Map(
            "event" -> LogEventKey.wechatSignature,
            "appid" -> c.ctx.appid.get,
            "openid" -> c.ctx.openid.get,
            "url" -> URLDecoder.decode(c.arg[String]("url"), "utf-8"),
            "ip" -> c.value.addressInfo.ip,
            "province" -> c.value.addressInfo.province,
            "city" -> c.value.addressInfo.city
          )
        ).toJson
      )
      val info = Map(
        "noncestr" -> UUIDUtil.uuid(),
        "timestamp" -> System.currentTimeMillis() / 1000,
        "url" -> URLDecoder.decode(c.arg[String]("url"), "utf-8")
      )
      WechatStream
        .jsapiQuery(c.ctx.appid.get)(c.ctx.system)
        .map(ticket => {
          info ++ Map(
            "jsapi_ticket" -> ticket
          )
        })
        .map(
          _.map(i => s"${i._1}=${i._2}").toSeq.sorted
            .mkString("&")
        )
        .map(DigestUtils.sha1Hex)
        .map((signature: String) => {
          WechatModel.SignatureResponse(
            appid = c.ctx.appid.get,
            nonceStr = info("noncestr").asInstanceOf[String],
            timestamp = info("timestamp").asInstanceOf[Long],
            signature = signature
          )
        })
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )

  val mutation = fields[SecureContext, RequestInfo](
    wechatLogin,
    signature
  )
}
