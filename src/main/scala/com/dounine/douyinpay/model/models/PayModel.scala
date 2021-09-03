package com.dounine.douyinpay.model.models

import java.time.LocalDateTime
import scala.xml.NodeSeq

object PayModel {

  case class PayInfo(
      id: String,
      money: Int,
      openid: String,
      pay: Boolean,
      payTime: LocalDateTime,
      createTime: LocalDateTime
  ) extends BaseSerializer

  case class UpdateCard(
      id: String,
      pay: Boolean
  ) extends BaseSerializer
}
