package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{OpenidModel, UserModel}
import com.dounine.douyinpay.router.routers.errors.{
  AuthException,
  LockedException
}
import com.dounine.douyinpay.router.routers.schema.SchemaDef.RequestInfo
import com.dounine.douyinpay.service.{OpenidStream, UserStream, WechatStream}

import scala.concurrent.Future

class SecureContext(val system: ActorSystem[_], val requestInfo: RequestInfo) {
  implicit val sys = system
  implicit val ec = system.executionContext
  implicit val materializer = SystemMaterializer(system).materializer
  var openid: Option[String] = None
  var appid: Option[String] = None
  def auth(): Future[OpenidModel.OpenidInfo] = {
    requestInfo.headers
      .get("token")
      .orElse(requestInfo.parameters.get("token")) match {
      case Some(token) =>
        Source
          .single(
            WechatStream.jwtDecode(token)
          )
          .collect {
            case Some(session) => session
            case None          => throw AuthException("token invalid")
          }
          .map(_.openid)
          .via(OpenidStream.query())
          .runWith(Sink.head)
          .collect {
            case Some(value) => value
            case None        => throw AuthException("user not found")
          }
      case None => throw AuthException("token required")
    }
  }
}
