package com.dounine.douyinpay.model.models

import com.dounine.douyinpay.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.douyinpay.model.types.service.PayPlatform.PayPlatform
import com.dounine.douyinpay.model.types.service.PayStatus.PayStatus
import eu.timepit.refined.api.Refined
import eu.timepit.refined.numeric.Positive

import java.time.LocalDateTime
import scala.concurrent.Promise

object OrderModel {

  case class OrderReport(
      payCount: Int,
      payMoney: Int,
      payPeople: Int,
      noPayCount: Int,
      noPayMoney: Int,
      noPayPeople: Int
  )

  final case class NewUserPay(
      openid: String,
      payMoney: Int,
      payCount: Int
  )

  final case class DbInfo(
      appid: String,
      ccode: String,
      openid: String,
      nickName: Option[String],
      pay: Boolean,
      expire: Boolean,
      orderId: String,
      id: String,
      money: Int,
      volumn: Int,
      fee: Int,
      platform: PayPlatform,
      createTime: LocalDateTime,
      payCount: Int = 0,
      payMoney: Int = 0,
      todayPayCount: Int = 0,
      todayPayMoney: Int = 0
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

  case class OrderCreateResponse(
      queryUrl: String,
      qrcodeUrl: String
  )

  final case class QrcodeResponse(
      message: Option[String],
      qrcode: Option[String],
      codeUrl: Option[String],
      code: Option[Int],
      setup: Option[String]
  ) extends BaseSerializer

  case class FutureCreateInfo(
      info: DbInfo,
      success: Promise[String]
  )

  final case class UpdateStatus(
      pay: Boolean,
      order: DbInfo
  ) extends BaseSerializer

  final case class Recharge(
      id: String,
      money: String,
      platform: PayPlatform,
      domain: String,
      ccode: String,
      sign: String
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

  final case class MoneyMenuItem(
      money: String,
      volumn: String,
      commonEnought: Boolean,
      vipEnought: Option[Boolean] = None,
      enought: Option[Boolean] = None
  )

  final case class MoneyMenuResponse(
      bu: Option[String] = None,
      list: List[MoneyMenuItem],
      targetUser: Boolean,
      commonRemain: Int,
      vipRemain: Option[String] = None,
      balance: Option[String] = None
  )
}
