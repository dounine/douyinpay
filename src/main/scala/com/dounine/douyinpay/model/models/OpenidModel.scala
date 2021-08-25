package com.dounine.douyinpay.model.models

import java.time.LocalDateTime

object OpenidModel {

  case class OpenidInfo(
      appid: String,
      openid: String,
      ccode: String,
      ip: String,
      locked: Boolean,
      createTime: LocalDateTime
  ) extends BaseSerializer

}
