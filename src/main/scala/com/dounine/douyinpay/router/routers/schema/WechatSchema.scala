package com.dounine.douyinpay.router.routers.schema

import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{UserModel, WechatModel}
import com.dounine.douyinpay.model.types.service.LogEventKey
import com.dounine.douyinpay.router.routers.SecureContext
import com.dounine.douyinpay.router.routers.errors.LockedException
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
import sangria.schema._

import java.util.UUID
import scala.concurrent.Future

object WechatSchema extends JsonParse {

  private val logger = LoggerFactory.getLogger(WechatStream.getClass)

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
            ccode = c.arg[String]("ccode"),
            token = c.value.headers.get("token"),
            sign = c.arg[String]("sign"),
            ip = c.value.addressInfo.ip
          )
        )
        .map(i => {
          if (
            MD5Util.md5(
              Array(i.ccode, i.code, i.token.getOrElse("")).sorted
                .mkString("")
            ) != i.sign
          ) {
            logger.error(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.wechatLoginSignError,
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
          } else {
            logger.info(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.wechatLogin,
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
                              "openid" -> result.get.openid,
                              "ip" -> c.value.addressInfo.ip,
                              "province" -> c.value.addressInfo.province,
                              "city" -> c.value.addressInfo.city
                            )
                          ).toJson
                        )
                        throw new LockedException(
                          "user locked -> " + result.get.openid
                        )
                      } else if (c.value.addressInfo.city == "济南") {
                        if (OpenidPaySuccess.query(session.openid) <= 2) {
                          logger.error(
                            Map(
                              "time" -> System.currentTimeMillis(),
                              "data" -> Map(
                                "event" -> LogEventKey.ipRangeLockedAccess,
                                "openid" -> result.get.openid,
                                "ip" -> c.value.addressInfo.ip,
                                "province" -> c.value.addressInfo.province,
                                "city" -> c.value.addressInfo.city
                              )
                            ).toJson
                          )
                          throw new LockedException(
                            "ip locked -> " + result.get.openid
                          )
                        }
                      } else {
                        logger.info(
                          Map(
                            "time" -> System.currentTimeMillis(),
                            "data" -> Map(
                              "event" -> LogEventKey.wechatLogin,
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
        .via(WechatStream.webBaseUserInfo2()(c.ctx.system))
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
  )

  val SignatureResponse =
    deriveObjectType[Unit, WechatModel.SignatureResponse](
      ObjectTypeName("SignatureResponse"),
      ObjectTypeDescription("授权"),
      DocumentField("noncestr", "随机字符串"),
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
            "url" -> c.arg[String]("url"),
            "ip" -> c.value.addressInfo.ip,
            "province" -> c.value.addressInfo.province,
            "city" -> c.value.addressInfo.city
          )
        ).toJson
      )
      val info = Map(
        "noncestr" -> UUIDUtil.uuid(),
        "timestamp" -> System.currentTimeMillis() / 1000,
        "url" -> c.arg[String]("url")
      )
      WechatStream
        .jsapiQuery()(c.ctx.system)
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
            noncestr = info("noncestr").asInstanceOf[String],
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
