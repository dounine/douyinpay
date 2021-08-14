package com.dounine.douyinpay.router.routers

import akka.http.scaladsl.common.{
  EntityStreamingSupport,
  JsonEntityStreamingSupport
}
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpResponse,
  RemoteAddress,
  StatusCodes
}
import akka.http.scaladsl.server.Directives.complete
import akka.http.scaladsl.server.{
  ExceptionHandler,
  RejectionHandler,
  StandardRoute
}
import akka.pattern.AskTimeoutException
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.dounine.douyinpay.model.models.RouterModel
import com.dounine.douyinpay.model.models.RouterModel.Fail
import com.dounine.douyinpay.model.types.router.ResponseCode
import com.dounine.douyinpay.tools.json.JsonParse

import java.net.InetAddress

trait SuportRouter extends JsonParse {

  implicit def rejectionHandler =
    RejectionHandler.default
      .mapRejectionResponse {
        case res @ HttpResponse(code, a, ent: HttpEntity.Strict, url) =>
          if (code.intValue() == 404) {
            res.withEntity(
              HttpEntity(
                ContentTypes.`application/json`,
                s"""{"status":"fail","msg": "resource not found"}"""
              )
            )
          } else {
            val message = ent.data.utf8String.replaceAll("\"", """\"""")
            res.withEntity(
              HttpEntity(
                ContentTypes.`application/json`,
                s"""{"status":"fail","msg": "$message"}"""
              )
            )
          }
        case x => x
      }

  implicit def exceptionHandler =
    ExceptionHandler {
      case timeout: AskTimeoutException =>
        fail("网络超时、请重试")
      case e: Exception =>
        fail(e.getMessage)
    }

  val timeoutResponse = HttpResponse(
    StatusCodes.OK,
    entity = HttpEntity(
      ContentTypes.`application/json`,
      """{"status":"fail","msg":"service timeout"}"""
    )
  )

  /**
    * 将source转成单个son流、默认是以数组的方式输入单个或多个流、例如：[{"code":"ok"}]
    */
  implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
    EntityStreamingSupport
      .json()
      .withFramingRenderer(
        Flow[ByteString]
      )

  def ok(data: Any): StandardRoute = {
    complete(RouterModel.Data(Option(data)))
  }

  def okData(data: Any): RouterModel.Data = {
    RouterModel.Data(Option(data))
  }

  val ok: StandardRoute = complete(RouterModel.Ok())

  def textResponse(data: String): HttpResponse = {
    HttpResponse(
      StatusCodes.OK,
      entity = HttpEntity(
        ContentTypes.`text/plain(UTF-8)`,
        data
      )
    )
  }

  def xmlResponse(data: Map[String, Any]): HttpResponse = {
    HttpResponse(
      StatusCodes.OK,
      entity = HttpEntity(
        ContentTypes.`text/xml(UTF-8)`,
        s"<xml>${data.map(i => s"<${i._1}>${i._2}</${i._1}>").mkString("")}</xml>"
      )
    )
  }

  def fail(msg: String): StandardRoute = {
    complete(RouterModel.Fail(Option(msg)))
  }

  def failMsg(msg: String): Fail = {
    RouterModel.Fail(Option(msg))
  }
  def failDataMsg(data: Any, msg: String): RouterModel.Data = {
    RouterModel.Data(
      data = Some(data),
      msg = Some(msg),
      status = ResponseCode.fail
    )
  }

  implicit class AdressExt(remoteAdress: RemoteAddress) {
    def getIp(): String = {
      remoteAdress
        .getAddress()
        .orElse(
          InetAddress.getByName("localhost")
        )
        .getHostAddress
    }
  }

  val failData = RouterModel.Fail()
  val fail: StandardRoute = complete(failData)
}
