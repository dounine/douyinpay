package com.dounine.douyinpay.model.types.service

object LogEventKey extends Enumeration {
  type LogEventKey = Value
  val wechatLogin: LogEventKey.Value = Value("event_wechat_login")
  val wechatLoginSignError: LogEventKey.Value = Value(
    "event_wechat_login_sign_error"
  )
  val fromCcode: LogEventKey.Value = Value("event_from_ccode")
  val orderCreateRequest: LogEventKey.Value = Value(
    "event_order_create_request"
  )

  val userLockedAccess: LogEventKey.Value = Value("event_user_locked_access")
  val orderCreateSignError: LogEventKey.Value = Value(
    "event_order_create_sign_error"
  )
  val orderCreateOk: LogEventKey.Value = Value("event_order_create_ok")
  val wechatSignature: LogEventKey.Value = Value("event_wechat_signature")
  val wechatMessage: LogEventKey.Value = Value("event_wechat_message")
  val orderCreateFail: LogEventKey.Value = Value("event_order_create_fail")
  val ipRangeLockedAccess: LogEventKey.Value = Value(
    "event_ip_range_locked_access"
  )
  val orderPayOk: LogEventKey.Value = Value("event_order_pay_ok")
  val orderPayManay: LogEventKey.Value = Value("event_order_pay_many")
  val payMoneyMenu: LogEventKey.Value = Value("event_pay_money_menu")
  val orderPayFail: LogEventKey.Value = Value("event_order_pay_fail")
  val orderQuery: LogEventKey.Value = Value("event_order_query")
  val userInfoQuery: LogEventKey.Value = Value("event_user_info_query")
  val accountPayRequest: LogEventKey.Value = Value("event_account_pay_request")
  val accountPayOk: LogEventKey.Value = Value("event_account_pay_ok")
  val accountPayFail: LogEventKey.Value = Value("event_account_pay_fail")
  val payQrcodeAccess: LogEventKey.Value = Value("event_pay_qrcode_access")
  val refundRequest: LogEventKey.Value = Value("event_refund_request")
  val refundOk: LogEventKey.Value = Value("event_refund_ok")
  val refundNotifySuccess: LogEventKey.Value = Value(
    "event_refund_notify_success"
  )
  val refundFail: LogEventKey.Value = Value("event_refund_fail")

}
