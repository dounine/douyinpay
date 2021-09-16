package com.dounine.douyinpay.tools.util

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes
}
import akka.stream.Materializer
import akka.util.ByteString
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.DingDing.MessageType.MessageType
import org.slf4j.LoggerFactory

import scala.concurrent.Future

object DingDing extends JsonParse {

  private val logger = LoggerFactory.getLogger(DingDing.getClass)
  object MessageType extends Enumeration {
    type MessageType = Value
    val system = Value("system_notify")
    val app = Value("app_notify")
    val order = Value("order_notify")
    val orderFail = Value("order_notify_fail")
    val payed = Value("payed_notify")
    val payerr = Value("payerr_notify")
    val event = Value("event_notify")
    val fans = Value("fans_notify")
    val message = Value("message_notify")
    val active = Value("active_notify")
    val phonePaySuccess = Value("phone_pay_success")
    val phonePayFail = Value("phone_pay_fail")
    val phonePayBind = Value("phone_pay_bind")
    val wechatPay = Value("wechat_pay_notify")
  }
  case class Markdown(
      title: String,
      text: String
  )
  case class MessageData(
      markdown: Markdown,
      msgtype: String = "markdown"
  )
  def sendMessage(
      mType: MessageType,
      data: MessageData,
      system: ActorSystem[_]
  ): Unit = {
    implicit val ec = system.executionContext
    sendMessageFuture(
      mType = mType,
      data = data
    )(system)
      .map(Right.apply)
      .recover {
        case e => Left(e.getMessage)
      }
      .foreach {
        case Left(value)  => logger.error(value)
        case Right(value) => logger.debug("消息发送成功 -> " + value)
      }
  }

  def sendMessageFuture(
      mType: MessageType,
      data: MessageData
  )(implicit system: ActorSystem[_]): Future[String] = {
    if (system.settings.config.getBoolean("app.pro")) {
      implicit val ec = system.executionContext
      Request
        .post[String](
          system.settings.config.getString(s"app.notify.${mType}"),
          data
        )
    } else Future.successful("dev")
  }

}
