package com.dounine.douyinpay.model.models

import java.time.LocalDateTime

object AccountModel {

  case class AccountInfo(
      openid: String,
      volumn: Int
  ) extends BaseSerializer

  case class AddVolumnToAccount(
      openid: String,
      cardId: String
  ) extends BaseSerializer
}
