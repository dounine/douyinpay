package com.dounine.douyinpay.model.models

import java.net.URLDecoder
import scala.xml.NodeSeq

object WechatModel {
  final case class Session(
      appid: String,
      openid: String,
      data: Option[Map[String, String]] = None,
      iat: Option[Long] = None, //issued time seconds
      exp: Option[Long] = None //expire time seconds
  ) extends BaseSerializer

  case class SignatureResponse(
      appid: String,
      nonceStr: String,
      timestamp: Long,
      signature: String
  )

  case class LoginParamers(
      scheme: String,
      appid: String,
      code: String,
      ccode: String,
      token: Option[String],
      sign: String,
      ip: String
  ) extends BaseSerializer

  case class WechatLoginResponse(
      redirect: Option[String] = None,
      open_id: Option[String] = None,
      token: Option[String] = None,
      expire: Option[Long] = None,
      admin: Option[Boolean] = None,
      sub: Option[Boolean] = None,
      needSub: Option[Boolean] = None,
      subUrl: Option[String] = None
  ) extends BaseSerializer

  case class AccessTokenBase(
      access_token: Option[String] = None,
      expires_in: Option[Int] = None,
      refresh_token: Option[String] = None,
      openid: Option[String] = None,
      scope: Option[String] = None,
      errcode: Option[String] = None,
      errmsg: Option[String] = None
  ) extends BaseSerializer

  case class SNSUserInfo(
      openid: String,
      nickname: String,
      sex: Int,
      province: String,
      city: String,
      country: String,
      headimgurl: String,
      privilege: Array[String],
      unionid: String
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
      subscribe_time: Option[Long],
      unionid: Option[String],
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

  case class SubscribeMsgPopup(
      templateId: String,
      subscribeStatusString: String,
      popupScene: String
  )

  object WechatMessage {
    def fromXml(appid: String, data: NodeSeq): WechatMessage = {
      val nodes = data \ "_"
      WechatMessage(
        appid = appid,
        toUserName = (data \ "ToUserName").text,
        fromUserName = (data \ "FromUserName").text,
        createTime = (data \ "CreateTime").text.toLong,
        msgType = (data \ "MsgType").text,
        subscribeMsgPopupEvent = nodes.find(_.label == "SubscribeMsgPopupEvent").map(popupEvent =>{
          (popupEvent \\ "List").map(node =>{
            SubscribeMsgPopup(
              templateId = (node \ "TemplateId").text,
              subscribeStatusString = (node \ "SubscribeStatusString").text,
              popupScene = (node \ "PopupScene").text
            )
          })
        }),
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
        url = nodes
          .find(_.label == "Url")
          .map(_.text)
          .map(URLDecoder.decode(_, "utf-8")),
        title = nodes.find(_.label == "Title").map(_.text),
        description = nodes.find(_.label == "Description").map(_.text)
      )
    }
  }
  case class WechatMessage(
      appid: String,
      toUserName: String,
      fromUserName: String,
      createTime: Long,
      msgType: String,
      subscribeMsgPopupEvent: Option[Seq[SubscribeMsgPopup]],
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
