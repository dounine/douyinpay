package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import com.dounine.douyinpay.model.models.{OrderModel, RouterModel}
import com.dounine.douyinpay.service.WechatStream
import com.dounine.douyinpay.tools.json.JsonParse
import pdi.jwt.{Jwt, JwtAlgorithm}

import scala.concurrent.Future
import scala.util.{Failure, Success, Try}

object TokenAuth extends JsonParse {

  val tokenName: String = "token"

  def apply(implicit system: ActorSystem[_]): Directive1[String] = {
    for {
      parameters <- parameterMap
      headerToken <- optionalHeaderValueByName(headerName = tokenName)
      userData <- jwtAuthenticateToken((headerToken match {
        case Some(token) => Map(tokenName -> token)
        case _           => Map.empty[String, String]
      }) ++ parameters)
    } yield userData
  }

  private val algorithms: Seq[JwtAlgorithm.HS256.type] = Seq(JwtAlgorithm.HS256)

  def jwtAuthenticateToken(
      params: Map[String, String]
  )(implicit system: ActorSystem[_]): Directive1[String] =
    for {
      authorizedToken <- checkAuthorization(params)
      decodedToken <- decodeToken(authorizedToken)
      userData <- convertToUserData(decodedToken)
    } yield userData

  private def convertToUserData(
      session: String
  ): Directive1[String] = {
    extractExecutionContext.flatMap { implicit ctx =>
      extractMaterializer.flatMap { implicit mat =>
        onComplete(Future.successful(session)).flatMap(handleError)
      }
    }
  }

  private def handleError(
      unmarshalledSession: Try[String]
  ): Directive1[String] =
    unmarshalledSession match {
      case Success(value)             => provide(value)
      case Failure(RejectionError(r)) => reject(r)
      case Failure(Unmarshaller.NoContentException) =>
        reject(RequestEntityExpectedRejection)
      case Failure(e: Unmarshaller.UnsupportedContentTypeException) =>
        reject(
          UnsupportedRequestContentTypeRejection(
            e.supported,
            e.actualContentType
          )
        )
      case Failure(x: IllegalArgumentException) =>
        reject(ValidationRejection(x.getMessage, Some(x)))
      case Failure(x) =>
        reject(MalformedRequestContentRejection(x.getMessage, x))
    }

  private def checkAuthorization(
      params: Map[String, String]
  ): Directive1[String] =
    params.get(tokenName) match {
      case Some(jwt) => provide(jwt)
      case None      => complete(RouterModel.Fail(Some("token字缺失")))
    }

  private def decodeToken(
      jwt: String
  )(implicit system: ActorSystem[_]): Directive1[String] = {
    WechatStream.jwtDecode(jwt) match {
      case Some(value) => provide(value)
      case None        => complete(RouterModel.Fail(Some("token失效")))
    }
  }
}
