package com.dounine.douyinpay.model.models

import java.time.LocalDateTime
import scala.xml.NodeSeq

object AccountModel {

  case class AccountInfo(
      openid: String,
      money: Int
  ) extends BaseSerializer

  case class AddVolumnToAccount(
      openid: String,
      money: BigDecimal
  ) extends BaseSerializer

  case class NotifyResponse(
      appid: String,
      bank_type: String,
      cash_fee: String,
      fee_type: String,
      is_subscribe: String,
      mch_id: String,
      nonce_str: String,
      openid: String,
      out_trade_no: String,
      result_code: String,
      return_code: String,
      sign: String,
      time_end: String,
      total_fee: String,
      trade_type: String,
      transaction_id: String
  )

  case class QueryPayResponse(
      pay: Boolean,
      createTime: Option[String] = None,
      money: Option[String] = None,
      balance: Option[String] = None,
      orderId: String
  )

  case class PayResponse(
      orderId: String,
      appId: String,
      timeStamp: String,
      nonceStr: String,
      `package`: String,
      signType: String,
      paySign: String
  )

  case class RechargeItem(
      money: String
  )

  case class AccountRechargeResponse(
      list: List[RechargeItem],
      balance: String,
      vipUser: Boolean
  )

  case class AccountLimit(
      cost: String,
      limitUse: String,
      freeUse: String,
      balance: String
  )
}
