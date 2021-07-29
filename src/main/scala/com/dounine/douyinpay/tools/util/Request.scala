package com.dounine.douyinpay.tools.util

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes,
  Uri
}
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.ByteString
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.json.JsonParse

import scala.concurrent.{ExecutionContextExecutor, Future}

object Request extends JsonParse {

  def get[T: Manifest](uri: Uri)(implicit system: ActorSystem[_]): Future[T] = {
    implicit val materializer: Materializer = SystemMaterializer(
      system
    ).materializer
    implicit val ec: ExecutionContextExecutor = system.executionContext
    Http(system)
      .singleRequest(
        request = HttpRequest(
          method = HttpMethods.GET,
          uri = uri
        ),
        settings = ConnectSettings.httpSettings(system)
      )
      .flatMap {
        case HttpResponse(code, value, entity, protocol) => {
          entity.dataBytes
            .runFold(ByteString.empty)(_ ++ _)
            .map(_.utf8String)
            .map(result => {
              result.getClass match {
                case t: T => result.asInstanceOf[T]
                case _    => result.jsonTo[T]
              }
            })
        }
        case msg => {
          Future.failed(new Exception(msg.toString()))
        }
      }
  }

  def post[T: Manifest](uri: Uri, data: Any)(implicit
      system: ActorSystem[_]
  ): Future[T] = {
    implicit val materializer: Materializer = SystemMaterializer(
      system
    ).materializer
    implicit val ec: ExecutionContextExecutor = system.executionContext
    Http(system)
      .singleRequest(
        request = HttpRequest(
          method = HttpMethods.POST,
          uri = uri,
          entity = HttpEntity(
            contentType = MediaTypes.`application/json`,
            string = data.toJson
          )
        ),
        settings = ConnectSettings.httpSettings(system)
      )
      .flatMap {
        case HttpResponse(code, value, entity, protocol) => {
          entity.dataBytes
            .runFold(ByteString.empty)(_ ++ _)
            .map(_.utf8String)
            .map(result => {
              result.getClass match {
                case t: T => result.asInstanceOf[T]
                case _    => result.jsonTo[T]
              }
            })
        }
        case msg => {
          Future.failed(new Exception(msg.toString()))
        }
      }
  }

}
