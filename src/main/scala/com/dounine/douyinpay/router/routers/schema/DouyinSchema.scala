package com.dounine.douyinpay.router.routers.schema

import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{
  OrderModel,
  PayUserInfoModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.service.{LogEventKey, PayPlatform}
import com.dounine.douyinpay.router.routers.SecureContext
import com.dounine.douyinpay.router.routers.errors.{
  LockedException,
  PayManyException
}
import com.dounine.douyinpay.router.routers.schema.SchemaDef.RequestInfo
import com.dounine.douyinpay.service.{OpenidStream, OrderStream, WechatStream}
import com.dounine.douyinpay.tools.akka.cache.CacheSource
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.{
  MD5Util,
  OpenidPaySuccess,
  Request,
  UUIDUtil
}
import org.slf4j.LoggerFactory
import sangria.macros.derive.{
  DocumentField,
  ObjectTypeDescription,
  ObjectTypeName,
  deriveObjectType
}
import sangria.schema._

import java.text.DecimalFormat
import java.time.LocalDateTime
import scala.concurrent.Future
import scala.concurrent.duration._

object DouyinSchema extends JsonParse {

  private val logger = LoggerFactory.getLogger(DouyinSchema.getClass)

  val UserInfoResponse =
    deriveObjectType[Unit, PayUserInfoModel.Info](
      ObjectTypeName("UserInfoResponse"),
      ObjectTypeDescription("用户信息"),
      DocumentField("nickName", " 昵称"),
      DocumentField("id", "用户id"),
      DocumentField("avatar", "头像地扯")
    )

  val userInfo = Field[
    SecureContext,
    RequestInfo,
    Option[PayUserInfoModel.Info],
    Option[PayUserInfoModel.Info]
  ](
    name = "userInfo",
    fieldType = OptionType(UserInfoResponse),
    tags = Authorised :: Nil,
    description = Some("查询用户信息"),
    arguments = Argument(
      name = "id",
      argumentType = StringType,
      description = "用户id"
    ) :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val s = c.ctx.system
      logger.info(
        Map(
          "time" -> System.currentTimeMillis(),
          "data" -> Map(
            "event" -> LogEventKey.userInfoQuery,
            "userAccount" -> c.arg[String]("id"),
            "openid" -> c.ctx.openid.get,
            "ip" -> c.value.addressInfo.ip,
            "province" -> c.value.addressInfo.province,
            "city" -> c.value.addressInfo.city
          )
        ).toJson
      )
      CacheSource(c.ctx.system)
        .cache()
        .orElse[Option[PayUserInfoModel.Info]](
          key = "userInfo_" + c.arg[String]("id"),
          ttl = 3.days,
          value = () =>
            Request
              .get[PayUserInfoModel.DouYinSearchResponse](
                s"https://webcast.amemv.com/webcast/user/open_info/?search_ids=${c
                  .arg[String]("id")}&aid=1128&source=1a0deeb4c56147d0f844d473b325a28b&fp=verify_khq5h2bx_oY8iEaW1_b0Yt_4Hvt_9PRa_3U70XFUYPgzI&t=${System
                  .currentTimeMillis()}"
              )
              .map(item => {
                if (item.data.open_info.nonEmpty) {
                  val data: PayUserInfoModel.DouYinSearchOpenInfo =
                    item.data.open_info.head
                  Some(
                    PayUserInfoModel.Info(
                      nickName = data.nick_name,
                      id = data.search_id,
                      avatar = data.avatar_thumb.url_list.head
                    )
                  )
                } else None
              })(c.ctx.system.executionContext)
        )
    }
  )

  val query = fields[SecureContext, RequestInfo](
    userInfo
  )
}
