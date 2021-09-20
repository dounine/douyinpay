package com.dounine.douyinpay.model.models

import com.dounine.douyinpay.model.types.service.PayPlatform.PayPlatform
import slick.lifted.Rep

import java.time.LocalDateTime

object BreakDownModel {

  final case class BreakDownInfo(
      id: String,
      orderId: String,
      account: String,
      platform: PayPlatform,
      appid: String,
      openid: String,
      success: Boolean,
      createTime: LocalDateTime
  )

}
