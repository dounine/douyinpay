package com.dounine.douyinpay.model.models

import com.dounine.douyinpay.model.types.service.IntervalStatus.IntervalStatus

import java.time.LocalDateTime

object StockFutunModel {

  final case class Info(
      symbol: String,
      interval: IntervalStatus
  ) extends BaseSerializer

}
