package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{HttpMethods, HttpRequest, HttpResponse}
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import com.dounine.douyinpay.model.models.{
  PayUserInfoModel,
  RouterModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.router.ResponseCode
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.json.JsonParse
import com.sksamuel.elastic4s.requests.cluster.NodeUsage
import org.slf4j.LoggerFactory
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

import java.time.Clock
import scala.concurrent.Future
import scala.util.Try

object WechatStream extends JsonParse {

  private val logger = LoggerFactory.getLogger(WechatStream.getClass)

  def jwtEncode(text: String)(implicit system: ActorSystem[_]): String = {
    val config = system.settings.config.getConfig("app")
    val jwtSecret = config.getString("jwt.secret")
    val jwtExpire = config.getDuration("jwt.expire").getSeconds
    Jwt.encode(
      JwtHeader(JwtAlgorithm.HS256),
      JwtClaim(text)
        .issuedAt(System.currentTimeMillis() / 1000)
        .expiresIn(jwtExpire)(Clock.systemUTC),
      jwtSecret
    )
  }

  def jwtDecode(
      text: String
  )(implicit system: ActorSystem[_]): Option[String] = {
    val config = system.settings.config.getConfig("app")
    val jwtSecret = config.getString("jwt.secret")
    if (Jwt.isValid(text, jwtSecret, Seq(JwtAlgorithm.HS256))) {
      val result: Try[(String, String, String)] =
        Jwt.decodeRawAll(
          text,
          jwtSecret,
          Seq(JwtAlgorithm.HS256)
        )
      Option(result.get._2)
    } else Option.empty
  }

  type Code = String
  type Openid = String
  def webBaseUserInfo(implicit
      system: ActorSystem[_]
  ): Flow[Code, RouterModel.Data, NotUsed] = {
    implicit val ec = system.executionContext
    implicit val materializer = SystemMaterializer(system).materializer
    val http = Http(system)
    val config = system.settings.config.getConfig("app")
    val appid = config.getString("wechat.appid")
    val secret = config.getString("wechat.secret")
    Flow[Code]
      .mapAsync(1) { code =>
        http
          .singleRequest(
            request = HttpRequest(
              method = HttpMethods.GET,
              uri =
                s"https://api.weixin.qq.com/sns/oauth2/access_token?appid=${appid}&secret=${secret}&code=${code}&grant_type=authorization_code"
            ),
            settings = ConnectSettings.httpSettings(system)
          )
          .flatMap {
            case HttpResponse(_, _, entity, _) =>
              entity.dataBytes
                .runFold(ByteString(""))(_ ++ _)
                .map(_.utf8String)
                .map(_.jsonTo[WechatModel.AccessTokenBase])
                .map(i => {
                  if (
                    i.errcode.getOrElse("") == "40163" || i.errcode
                      .getOrElse("") == "40029"
                  ) { //code已使用过或code过期
                    RouterModel.Data(
                      status = ResponseCode.fail,
                      msg = Some("认证失效、重新登录"),
                      data = Some(
                        Map(
                          "redirect" -> "https://open.weixin.qq.com/connect/oauth2/authorize?appid=wx7b168b095eb4090e&redirect_uri=http%3A%2F%2Fkdc.kuaiyugo.com&response_type=code&scope=snsapi_base&state=wx7b168b095eb4090e#wechat_redirect"
                        )
                      )
                    )
                  } else {
                    RouterModel.Data(
                      Some(
                        Map(
                          "open_id" -> i.openid.get,
                          "token" -> jwtEncode(i.openid.get)
                        )
                      )
                    )
                  }
                })
            case msg @ _ =>
              logger.error(s"请求失败 $msg")
              Future.failed(new Exception(s"请求失败 $msg"))
          }
      }
  }
}
