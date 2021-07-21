package com.dounine.douyinpay.model.models

import com.dounine.douyinpay.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.douyinpay.model.types.service.PayPlatform.PayPlatform
import com.dounine.douyinpay.model.types.service.PayStatus.PayStatus
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

import java.time.LocalDateTime
import scala.concurrent.Promise

object OrderModel {

  final case class DbInfo(
      openid: String,
      nickName: Option[String],
      pay: Boolean,
      expire: Boolean,
      orderId: String,
      id: String,
      money: Int,
      volumn: Int,
      platform: PayPlatform,
      createTime: LocalDateTime,
      payCount: Int,
      payMoney: Int
  ) extends BaseSerializer {
    override def hashCode(): Int = orderId.hashCode()

    override def equals(obj: Any): Boolean = {
      if (obj == null) {
        false
      } else {
        obj.asInstanceOf[DbInfo].orderId == orderId
      }
    }
  }

  case class FutureCreateInfo(
      info: DbInfo,
      success: Promise[String]
  )

  final case class Recharge(
      openid: String,
      nickName: Option[String],
      id: String,
      platform: PayPlatform,
      money: String,
      volumn: String
  ) extends BaseSerializer

  final case class Cancel(
      apiKey: String,
      orderId: Option[String],
      outOrder: Option[String],
      sign: String
  ) extends BaseSerializer

  final case class Query(
      orderId: Option[String],
      outOrder: Option[String],
      sign: String
  ) extends BaseSerializer

  final case class Balance(
      apiKey: String,
      sign: String
  ) extends BaseSerializer

  final case class CallbackInfo(
      apiKey: String,
      orderId: String,
      outOrder: String,
      money: Int,
      account: String,
      platform: PayPlatform,
      status: PayStatus,
      sign: String,
      msg: Option[String]
  ) extends BaseSerializer

  final case class UserInfo(
      nickName: String,
      id: String,
      avatar: String
  ) extends BaseSerializer

  final case class DouYinSearchAvatarThumb(
      avg_color: String,
      height: Long,
      image_type: Int,
      is_animated: Boolean,
      open_web_url: String,
      uri: String,
      url_list: Seq[String],
      width: Long
  )

  final case class DouYinSearchOpenInfo(
      avatar_thumb: DouYinSearchAvatarThumb,
      nick_name: String,
      search_id: String
  )

  final case class DouYinSearchData(
      open_info: Seq[DouYinSearchOpenInfo]
  )

  final case class DouYinSearchResponse(
      status_code: Int,
      data: DouYinSearchData
  )

  final case class CreateOrderSuccess(
      orderId: String,
      outOrder: String,
      balance: String,
      margin: String
  ) extends BaseSerializer

}
