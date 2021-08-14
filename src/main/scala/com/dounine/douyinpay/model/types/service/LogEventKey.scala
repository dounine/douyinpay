package com.dounine.douyinpay.model.types.service

object LogEventKey extends Enumeration {
  type LogEventKey = Value
  val wechatLogin: LogEventKey.Value = Value("event_wechat_login")
  val fromCcode: LogEventKey.Value = Value("event_from_ccode")
  val orderCreateRequest: LogEventKey.Value = Value(
    "event_order_create_request"
  )
  val orderCreateOk: LogEventKey.Value = Value("event_order_create_ok")
  val orderCreateFail: LogEventKey.Value = Value("event_order_create_fail")
  val orderPayOk: LogEventKey.Value = Value("event_order_pay_ok")
  val payMoneyMenu: LogEventKey.Value = Value("event_pay_money_menu")
  val orderPayFail: LogEventKey.Value = Value("event_order_pay_fail")
  val userInfoQuery: LogEventKey.Value = Value("event_user_info_query")
  val payQrcodeAccess: LogEventKey.Value = Value("event_pay_qrcode_access")

}
