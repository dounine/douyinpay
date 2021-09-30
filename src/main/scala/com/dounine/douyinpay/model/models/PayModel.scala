package com.dounine.douyinpay.model.models

import com.dounine.douyinpay.model.types.service.PayStatus.PayStatus

import java.time.LocalDateTime
import scala.xml.NodeSeq

object PayModel {

  case class PayInfo(
      id: String,
      money: Int,
      openid: String,
      pay: PayStatus,
      payTime: LocalDateTime,
      createTime: LocalDateTime
  ) extends BaseSerializer

  case class UpdateCard(
      id: String,
      pay: PayStatus
  ) extends BaseSerializer

  case class PayReport(
      payedCount: Int,
      payedMoney: Int,
      payedPeople: Int,
      refundCount: Int,
      refundMoney: Int,
      refundPeople: Int
  )

  case class NewUserPay(
      openid: String,
      payMoney: Int,
      payCount: Int
  )
}
