package com.dounine.douyinpay.model.models

import java.time.LocalDateTime
import scala.xml.NodeSeq

object CardModel {

  case class CardInfo(
      id: String,
      money: BigDecimal,
      openid: Option[String],
      pay: Boolean,
      payTime: LocalDateTime,
      createTime: LocalDateTime
  ) extends BaseSerializer

  case class UpdateCard(
      id: String,
      pay: Boolean
  ) extends BaseSerializer
}
