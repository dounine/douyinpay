package com.dounine.douyinpay.model.models

import java.time.LocalDateTime

object AccountModel {

  case class AccountInfo(
      openid: String,
      money: BigDecimal
  ) extends BaseSerializer

  case class AddVolumnToAccount(
      openid: String,
      money: BigDecimal
  ) extends BaseSerializer
}
