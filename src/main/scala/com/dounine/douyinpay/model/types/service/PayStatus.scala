package com.dounine.douyinpay.model.types.service

object PayStatus extends Enumeration {
  type PayStatus = Value
  val dbLength: Int = 10
  val normal: PayStatus.Value = Value("normal")
  val payed: PayStatus.Value = Value("payed")
  val payerr: PayStatus.Value = Value("payerr")
  val refunding: PayStatus.Value = Value("refunding")
  val refund: PayStatus.Value = Value("refund")
  val cancel: PayStatus.Value = Value("cancel")

  val list: Seq[PayStatus.Value] = Seq(normal, payed, payerr, cancel, refund)
}
