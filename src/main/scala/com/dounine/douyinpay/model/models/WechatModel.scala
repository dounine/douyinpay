package com.dounine.douyinpay.model.models

object WechatModel {
  case class AccessTokenBase(
      access_token: Option[String],
      expires_in: Option[Int],
      refresh_token: Option[String],
      openid: Option[String],
      scope: Option[String],
      errcode: Option[String],
      errmsg: Option[String]
  ) extends BaseSerializer
}
