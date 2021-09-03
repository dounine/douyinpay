package com.dounine.douyinpay.tools.util

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.SystemMaterializer
import akka.util.ByteString
import com.dounine.douyinpay.tools.json.JsonParse

import scala.concurrent.Future

object WeixinPay extends JsonParse {

  case class Order(
      appid: String,
      mch_id: String,
      device_info: Option[String] = None,
      nonce_str: String,
      sign: Option[String] = None,
      sign_type: Option[String] = None,
      body: String,
      attach: Option[String] = None,
      out_trade_no: String,
      fee_type: Option[String] = None,
      total_fee: String,
      spbill_create_ip: String,
      time_start: Option[String] = None,
      time_expire: Option[String] = None,
      goods_tag: Option[String] = None,
      notify_url: String,
      trade_type: String,
      product_id: Option[String] = None,
      limit_pay: Option[String] = None,
      openid: String
  )

  case class OrderResponse(
      return_msg: String,
      return_code: String,
      result_code: String,
      mch_id: String,
      appid: String,
      nonce_str: String,
      sign: String,
      prepay_id: String,
      trade_type: String
  )

  def generateSignature(
      data: Map[String, String],
      appid: String
  )(implicit system: ActorSystem[_]): Map[String, String] = {
    val key =
      system.settings.config.getString(s"app.wechat.${appid}.pay.key")
    val sign = MD5Util
      .md5(
        data.toList
          .filterNot(_._1 == "sign")
          .sortBy(_._1)
          .map(i => s"${i._1}=${i._2}")
          .mkString("&") + s"&key=${key}"
      )
      .toUpperCase()
    data + ("sign" -> sign)
  }

  def isSignatureValid(data: Map[String, String], sign: String)(implicit
      system: ActorSystem[_]
  ): Boolean = {
    val key =
      system.settings.config.getString(s"app.wechat.${data("appid")}.pay.key")
    sign == MD5Util
      .md5(
        data.toList
          .filterNot(_._1 == "sign")
          .sortBy(_._1)
          .map(i => s"${i._1}=${i._2}")
          .mkString("&") + s"&key=${key}"
      )
      .toUpperCase()
  }

  def unifiedOrder(
      order: Order
  )(implicit system: ActorSystem[_]): Future[Map[String, String]] = {
    val http = Http(system)
    implicit val materializer = SystemMaterializer(system).materializer
    implicit val ec = system.executionContext
    val data =
      generateSignature(data = order.toMap[String], appid = order.appid)

    http
      .singleRequest(
        request = HttpRequest(
          method = HttpMethods.POST,
          uri = "https://api.mch.weixin.qq.com/pay/unifiedorder",
          entity = HttpEntity(
            ContentTypes.`text/xml(UTF-8)`,
            data.toXml(Some("xml"))
          )
        )
      )
      .flatMap {
        case HttpResponse(code, value, entity, protocol) => {
          entity.dataBytes
            .runFold(ByteString.empty)(_ ++ _)
            .map(_.utf8String)
            .map(_.childXmlTo[Map[String, String]])
        }
        case msg => Future.failed(new Exception(msg.toString()))
      }
  }

}
