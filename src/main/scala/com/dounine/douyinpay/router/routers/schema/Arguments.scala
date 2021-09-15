package com.dounine.douyinpay.router.routers.schema

import sangria.schema.{Argument, StringType}

object Arguments {

  val orderId = Argument(
    name = "orderId",
    argumentType = StringType,
    description = "定单ID"
  )

  val money = Argument(
    name = "money",
    argumentType = StringType,
    description = "充值金额"
  )
  val id = Argument(
    name = "id",
    argumentType = StringType,
    description = "充值帐号"
  )
  val volumn = Argument(
    name = "volumn",
    argumentType = StringType,
    description = "充值币数"
  )
  val ccode = Argument(
    name = "ccode",
    argumentType = StringType,
    description = "渠道"
  )
  val domain = Argument(
    name = "domain",
    argumentType = StringType,
    description = "domain"
  )
  val platform = Argument(
    name = "platform",
    argumentType = Enums.PayPlatformType,
    description = "平台"
  )
  val sign = Argument(
    name = "sign",
    argumentType = StringType,
    description =
      "签名md5((id,platform,money,volumn,domain,openid).sort.join(''))"
  )
}
