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

  case class BillItem(
      id: String,
      money: String,
      canRefund: Boolean,
      refund: Option[String] = None,
      status: String,
      createTime: String
  )

  case class BillResponse(
      list: List[BillItem],
      sum: String
  )

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

  case class RefundNotifyResponse(
      return_code: String,
      appid: String,
      mch_id: String,
      nonce_str: String,
      req_info: String
  )

  case class RefundNotifySuccessDetail(
      transaction_id: String,
      out_trade_no: String,
      refund_id: String,
      out_refund_no: String,
      refund_recv_accout: String,
      total_fee: String,
      refund_fee: String,
      settlement_refund_fee: String,
      refund_status: String,
      success_time: String,
      refund_request_source: String
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

  case class RefundResponse(
      msg: String
  )

  case class WechatRefundResponse(
      return_code: String,
      return_msg: String
  )
}
