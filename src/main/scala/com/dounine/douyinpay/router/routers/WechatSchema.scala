package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{UserModel, WechatModel}
import com.dounine.douyinpay.model.types.service.LogEventKey
import com.dounine.douyinpay.router.routers.SchemaDef.RequestInfo
import com.dounine.douyinpay.service.WechatStream
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.MD5Util
import org.slf4j.LoggerFactory
import sangria.schema._

import java.net.InetAddress

object WechatSchema extends JsonParse {

  private val logger = LoggerFactory.getLogger(WechatStream.getClass)
  val WechatLoginResponse = ObjectType(
    name = "Response",
    description = "登录响应",
    fields = fields[Unit, WechatModel.WechatLoginResponse](
      Field(
        name = "redirect",
        fieldType = OptionType(StringType),
        description = Some("code失效重定向登录地扯"),
        resolve = _.value.redirect
      ),
      Field(
        name = "open_id",
        fieldType = OptionType(StringType),
        description = Some("微信open_id"),
        resolve = _.value.open_id
      ),
      Field(
        name = "token",
        fieldType = OptionType(StringType),
        description = Some("登录token"),
        resolve = _.value.token
      ),
      Field(
        name = "enought",
        fieldType = OptionType(BooleanType),
        description = Some("余额是否足够"),
        resolve = _.value.enought
      ),
      Field(
        name = "expire",
        fieldType = OptionType(LongType),
        description = Some("token过期时间"),
        resolve = _.value.expire
      ),
      Field(
        name = "admin",
        fieldType = OptionType(BooleanType),
        description = Some("是否是管理员"),
        resolve = _.value.admin
      )
    )
  )

  val mutation = fields[ActorSystem[_], RequestInfo](
    Field(
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
      resolve = c =>
        Source
          .single(
            WechatModel.LoginParamers(
              code = c.arg[String]("code"),
              ccode = c.arg[String]("ccode"),
              token = c.value.headers.get("token"),
              sign = c.arg[String]("sign"),
              ip = Seq("X-Forwarded-For", "X-Real-Ip", "Remote-Address")
                .map(c.value.headers.get)
                .find(_.isDefined)
                .flatMap(_.map(i => i.split(",").head))
                .getOrElse("localhost")
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
                    "ip" -> i.ip
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
                    "ip" -> i.ip
                  )
                ).toJson
              )
            }
            i
          })
          .via(WechatStream.webBaseUserInfo2()(c.ctx))
          .runWith(Sink.head)(SystemMaterializer(c.ctx).materializer)
    )
  )
}
