package com.dounine.douyinpay.model.models

import java.time.LocalDateTime

object OpenidModel {

  case class OpenidInfo(
      openid: String,
      ccode: String,
      ip: String,
      createTime: LocalDateTime
  ) extends BaseSerializer

}
