package com.dounine.douyinpay.model.models

import java.time.LocalDateTime

object TokenModel {

  case class TokenResult(
      token: String,
      expire: LocalDateTime
  )
  case class TokenResponse(
      data: TokenResult,
      status: String
  ) extends BaseSerializer

  case class TickResult(
      tick: String,
      expire: LocalDateTime
  )
  case class TickResponse(
      data: TickResult,
      status: String
  ) extends BaseSerializer

}
