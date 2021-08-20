package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{HttpRequest, RemoteAddress}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshaller
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{RouterModel, WechatModel}
import com.dounine.douyinpay.model.types.service.LogEventKey
import com.dounine.douyinpay.router.routers.errors.LockedException
import com.dounine.douyinpay.service.{OpenidStream, WechatStream}
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.{IpUtils, OpenidPaySuccess}
import org.slf4j.LoggerFactory

import java.net.InetAddress
import scala.util.{Failure, Success, Try}

object TokenAuth extends JsonParse {

  private val logger = LoggerFactory.getLogger(TokenAuth.getClass)
  val tokenName: String = "token"

  def apply()(implicit
      system: ActorSystem[_]
  ): Directive1[WechatModel.Session] = {
    for {
      parameters <- parameterMap
      headerToken <- optionalHeaderValueByName(headerName = tokenName)
      ip <- extractClientIP
      request <- extractRequest
      userData <- jwtAuthenticateToken(
        (headerToken match {
          case Some(token) => Map(tokenName -> token)
          case _           => Map.empty[String, String]
        }) ++ parameters,
        ip,
        request
      )
    } yield userData
  }

  def jwtAuthenticateToken(
      params: Map[String, String],
      ip: RemoteAddress,
      request: HttpRequest
  )(implicit system: ActorSystem[_]): Directive1[WechatModel.Session] =
    for {
      authorizedToken <- checkAuthorization(params)
      decodedToken <- decodeToken(authorizedToken)
      userData <- convertToUserData(decodedToken, ip, request)
    } yield userData

  private def convertToUserData(
      session: WechatModel.Session,
      ip: RemoteAddress,
      request: HttpRequest
  )(implicit system: ActorSystem[_]): Directive1[WechatModel.Session] = {
    extractExecutionContext.flatMap { implicit ctx =>
      extractMaterializer.flatMap { implicit mat =>
        val userInfo = Source
          .single(session.openid)
          .via(OpenidStream.query())
          .map(_.get)
          .map(info => {
            val ipHost = ip
              .getAddress()
              .orElse(
                InetAddress.getByName("127.0.0.1")
              )
              .getHostAddress
            val (province, city) =
              IpUtils.convertIpToProvinceCity(ipHost)
            if (info.locked) {
              logger.error(
                Map(
                  "time" -> System.currentTimeMillis(),
                  "data" -> Map(
                    "event" -> LogEventKey.userLockedAccess,
                    "openid" -> session.openid,
                    "uri" -> request.uri,
                    "ip" -> ipHost,
                    "province" -> province,
                    "city" -> city
                  )
                ).toJson
              )
              throw new LockedException(s"user locked -> ${info.openid} ")
            } else if (city == "济南") {
              if (OpenidPaySuccess.query(session.openid) <= 2) {
                logger.error(
                  Map(
                    "time" -> System.currentTimeMillis(),
                    "data" -> Map(
                      "event" -> LogEventKey.ipRangeLockedAccess,
                      "openid" -> session.openid,
                      "uri" -> request.uri,
                      "ip" -> ipHost,
                      "province" -> province,
                      "city" -> city
                    )
                  ).toJson
                )
                throw new LockedException(
                  s"ip locked -> ${info.openid}"
                )
              }
            }
            info
          })
          .runWith(Sink.head)
        onComplete(
          userInfo.map(i => session)
        ).flatMap(handleError)
      }
    }
  }

  private def handleError(
      unmarshalledSession: Try[WechatModel.Session]
  ): Directive1[WechatModel.Session] =
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
      case Failure(x: LockedException) =>
        x.printStackTrace()
        reject(ValidationRejection("Internal server error", Some(x)))
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
  )(implicit system: ActorSystem[_]): Directive1[WechatModel.Session] = {
    WechatStream.jwtDecode(jwt) match {
      case Some(value) => provide(value)
      case None        => complete(RouterModel.Fail(Some("token失效")))
    }
  }
}
