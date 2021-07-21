package com.dounine.douyinpay.model.models

import java.time.LocalDateTime

object DictionaryModel {

  final case class DbInfo(
      key: String,
      text: String,
      createTime: LocalDateTime
  )

}
