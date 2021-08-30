package com.dounine.douyinpay.model.types.service

object PayPlatform extends Enumeration {
  type PayPlatform = Value
  val dbLength: Int = 8
  val douyin: PayPlatform.Value = Value("douyin")
  val kuaishou: PayPlatform.Value = Value("kuaishou")
  val huoshan: PayPlatform.Value = Value("huoshan")

  val list: Seq[PayPlatform.Value] = Seq(douyin, kuaishou, huoshan)
}
