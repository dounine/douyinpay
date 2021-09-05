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
  EnumTypeDescription,
  EnumTypeName,
  ObjectTypeDescription,
  ObjectTypeName,
  deriveEnumType,
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

  val PayPlatformType = EnumType[PayPlatform.PayPlatform](
    "Platform",
    Some("充值平台"),
    values = List(
      EnumValue(
        "douyin",
        value = PayPlatform.douyin,
        description = Some("抖音")
      ),
      EnumValue(
        "kuaishou",
        value = PayPlatform.kuaishou,
        description = Some("快手")
      ),
      EnumValue(
        "huoshan",
        value = PayPlatform.huoshan,
        description = Some("抖音火山视频")
      )
    )
  )

  val IdArg = Argument(
    name = "id",
    argumentType = StringType,
    description = "用户id"
  )

  val PlatformArg = Argument(
    name = "platform",
    argumentType = PayPlatformType,
    description = "充值平台"
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
    arguments = IdArg :: PlatformArg :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val s = c.ctx.system
      val id = c.arg(IdArg).replaceAll("[^A-Za-z0-9_.]", "")
      val platform = c.arg(PlatformArg)

      logger.info(
        Map(
          "time" -> System.currentTimeMillis(),
          "data" -> Map(
            "event" -> LogEventKey.userInfoQuery,
            "userAccount" -> id,
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
          key = "userInfo_" + platform + id,
          ttl = 3.days,
          value = () =>
            platform match {
              case PayPlatform.douyin | PayPlatform.huoshan =>
                val url = platform match {
                  case PayPlatform.douyin =>
                    s"https://webcast.amemv.com/webcast/user/open_info/?search_ids=${id}&aid=1128&source=1a0deeb4c56147d0f844d473b325a28b&fp=verify_khq5h2bx_oY8iEaW1_b0Yt_4Hvt_9PRa_3U70XFUYPgzI&t=${System
                      .currentTimeMillis()}"
                  case PayPlatform.huoshan =>
                    s"https://webcast.huoshan.com/webcast/user/open_info/?search_ids=${id}&aid=1112&source=15002a5b3205f64e6d8749b4343a8c12&fp=verify_kstrxrvd_bUQj6dLx_j37A_4Xpb_BY2Q_ICBYpTpshQ0s&t=${System.currentTimeMillis()}"
                }
                Request
                  .get[PayUserInfoModel.DouYinSearchResponse](
                    url
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
              case PayPlatform.huoshan => Future.successful(None)
            }
        )
    }
  )

  val query = fields[SecureContext, RequestInfo](
    userInfo
  )
}
