package com.dounine.douyinpay.model.models

import scala.xml.NodeSeq

object WechatModel {
  final case class Session(
      openid: String,
      data: Option[Map[String, String]] = None,
      iat: Option[Long] = None, //issued time seconds
      exp: Option[Long] = None //expire time seconds
  ) extends BaseSerializer

  case class AccessTokenBase(
      access_token: Option[String],
      expires_in: Option[Int],
      refresh_token: Option[String],
      openid: Option[String],
      scope: Option[String],
      errcode: Option[String],
      errmsg: Option[String]
  ) extends BaseSerializer

  case class WechatUserInfo(
      subscribe: Int,
      openid: Option[String],
      nickname: Option[String],
      sex: Option[Int],
      language: Option[String],
      city: Option[String],
      province: Option[String],
      country: Option[String],
      headimgurl: Option[Long],
      remark: Option[String],
      subscribe_scene: Option[String],
      qr_scene: Option[Long],
      qr_scene_str: Option[String]
  ) extends BaseSerializer

  case class JsApiTicket(
      errcode: Int,
      errmsg: String,
      ticket: Option[String],
      expires_in: Option[Int]
  ) extends BaseSerializer

  object WechatMessage {
    def fromXml(data: NodeSeq): WechatMessage = {
      val nodes = data \ "_"
      WechatMessage(
        toUserName = (data \ "ToUserName").text,
        fromUserName = (data \ "FromUserName").text,
        createTime = (data \ "CreateTime").text.toLong,
        msgType = (data \ "MsgType").text,
        msgId = nodes.find(_.label == "MsgId").map(_.text.toLong),
        event = nodes.find(_.label == "Event").map(_.text),
        eventKey = nodes.find(_.label == "EventKey").map(_.text),
        content = nodes.find(_.label == "Content").map(_.text),
        ticket = nodes.find(_.label == "Ticket").map(_.text),
        latitude = nodes.find(_.label == "Latitude").map(_.text),
        longitude = nodes.find(_.label == "Longitude").map(_.text),
        precision = nodes.find(_.label == "Precision").map(_.text),
        mediaId = nodes.find(_.label == "MediaId").map(_.text),
        format = nodes.find(_.label == "Format").map(_.text),
        recognition = nodes.find(_.label == "Recognition").map(_.text),
        thumbMediaId = nodes.find(_.label == "ThumbMediaId").map(_.text),
        locationX = nodes.find(_.label == "Location_X").map(_.text),
        locationY = nodes.find(_.label == "Location_Y").map(_.text),
        scale = nodes.find(_.label == "Scale").map(_.text),
        label = nodes.find(_.label == "Label").map(_.text),
        url = nodes.find(_.label == "Url").map(_.text),
        title = nodes.find(_.label == "Title").map(_.text),
        description = nodes.find(_.label == "Description").map(_.text)
      )
    }
  }
  case class WechatMessage(
      toUserName: String,
      fromUserName: String,
      createTime: Long,
      msgType: String,
      msgId: Option[Long],
      event: Option[String],
      eventKey: Option[String],
      content: Option[String],
      mediaId: Option[String],
      format: Option[String],
      recognition: Option[String],
      thumbMediaId: Option[String],
      locationX: Option[String],
      locationY: Option[String],
      scale: Option[String],
      label: Option[String],
      url: Option[String],
      title: Option[String],
      description: Option[String],
      ticket: Option[String],
      latitude: Option[String],
      longitude: Option[String],
      precision: Option[String]
  )

}