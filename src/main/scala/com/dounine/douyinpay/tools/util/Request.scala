package com.dounine.douyinpay.tools.util

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.Cookie
import akka.http.scaladsl.model.{
  FormData,
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes,
  StatusCode,
  StatusCodes,
  Uri
}
import akka.stream.{Materializer, SystemMaterializer}
import akka.util.ByteString
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.json.JsonParse

import scala.reflect._
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
        case HttpResponse(code: StatusCode, value, entity, protocol) =>
          code.intValue() match {
            case 200 =>
              entity.dataBytes
                .runFold(ByteString.empty)(_ ++ _)
                .map(_.utf8String)
                .map((result: String) => {
                  classTag[T].toString() match {
                    case "java.lang.String" => result.asInstanceOf[T]
                    case _                  => result.jsonTo[T]
                  }
                })
            case value @ _ => Future.failed(new Exception(s"code is ${value}"))
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
        case HttpResponse(code, value, entity, protocol) =>
          code.intValue() match {
            case 200 =>
              entity.dataBytes
                .runFold(ByteString.empty)(_ ++ _)
                .map(_.utf8String)
                .map((result: String) => {
                  classTag[T].toString() match {
                    case "java.lang.String" => result.asInstanceOf[T]
                    case _                  => result.jsonTo[T]
                  }
                })
            case value @ _ => Future.failed(new Exception(s"code is ${value}"))
          }
        case msg => {
          Future.failed(new Exception(msg.toString()))
        }
      }
  }

  def postFormData[T: Manifest](uri: Uri, data: Map[String, String])(implicit
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
          entity = FormData(
            data
          ).toEntity,
          headers = Seq(
            Cookie(
              "acf_auth",
              "8a82sdmYcHoQu9kbxUqYQjiYDbvbSTZVEBt3RsBRqn52rQUdpD414CvstyB8liIXJ%2B15QiOA0%2BuP8eXAuI8rjPtxT6F7qTqLuuvyEweho%2F%2BXuL2kwfrRPI0"
            )
          )
        ),
        settings = ConnectSettings.httpSettings(system)
      )
      .flatMap {
        case HttpResponse(code, value, entity, protocol) =>
          code.intValue() match {
            case 200 =>
              entity.dataBytes
                .runFold(ByteString.empty)(_ ++ _)
                .map(_.utf8String)
                .map((result: String) => {
                  classTag[T].toString() match {
                    case "java.lang.String" => result.asInstanceOf[T]
                    case _                  => result.jsonTo[T]
                  }
                })
            case value @ _ => Future.failed(new Exception(s"code is ${value}"))
          }
        case msg => {
          Future.failed(new Exception(msg.toString()))
        }
      }
  }

}
