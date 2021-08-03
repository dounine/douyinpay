package com.dounine.douyinpay.model.models

import java.time.LocalDateTime
import scala.xml.NodeSeq

object CardModel {

  case class CardInfo(
      id: String,
      money: Int,
      openid: Option[String],
      activeTime: LocalDateTime,
      createTime: LocalDateTime
  ) extends BaseSerializer

  case class UpdateCard(
      id: String,
      openid: String
  ) extends BaseSerializer
}
