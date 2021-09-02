package com.dounine.douyinpay.router.routers

import akka.actor
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server._
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.dounine.douyinpay.behaviors.engine.AccessTokenBehavior
import com.dounine.douyinpay.model.models.{
  BaseSerializer,
  OpenidModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.service.LogEventKey
import com.dounine.douyinpay.service.{OpenidStream, WechatStream}
import com.dounine.douyinpay.tools.util.IpUtils
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.{Logger, LoggerFactory}

import java.net.{Inet4Address, InetAddress, URLEncoder}
import java.util.UUID
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.xml.NodeSeq

class WechatRouter()(implicit system: ActorSystem[_])
    extends SuportRouter
    with ScalaXmlSupport {

  case class Hello(name: String) extends BaseSerializer
  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[WechatRouter])
  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val actorSystem: actor.ActorSystem = materializer.system
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val config = system.settings.config.getConfig("app")

  val sharding = ClusterSharding(system)

  val keyFunction: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext => r.request.uri
  }

  val defaultCachingSettings = CachingSettings(system)
  val lfuCacheSettings = defaultCachingSettings.lfuCacheSettings
    .withInitialCapacity(100)
    .withMaxCapacity(1000)
    .withTimeToLive(3.days)
    .withTimeToIdle(1.days)

  val cachingSettings =
    defaultCachingSettings.withLfuCacheSettings(lfuCacheSettings)
  val lfuCache: Cache[Uri, RouteResult] = LfuCache(cachingSettings)

  val domain = config.getString("file.domain")
  val http = Http(system)

  val route: Route =
    cors() {
      pathPrefix("wechat") {
        concat(
          get {
            path("into" / "from" / Segment) {
              ccode: String =>
                extractClientIP {
                  ip =>
                    extractRequest {
                      request =>
                        val scheme: String = request.headers
                          .map(i => i.name() -> i.value())
                          .toMap
                          .getOrElse("X-Scheme", request.uri.scheme)

                        val (province, city) =
                          IpUtils.convertIpToProvinceCity(ip.getIp())
                        logger.info(
                          Map(
                            "time" -> System.currentTimeMillis(),
                            "data" -> Map(
                              "event" -> LogEventKey.fromCcode,
                              "ccode" -> ccode,
                              "ip" -> ip.getIp(),
                              "province" -> province,
                              "city" -> city
                            )
                          ).toJson
                        )
                        val params: String = Map(
                          "appid" -> "wx7b168b095eb4090e",
                          "redirect_uri" -> URLEncoder.encode(
                            (scheme + "://" + domain) + s"/pages/douyin/index?ccode=${ccode}&platform=douyin&appid=wx7b168b095eb4090e",
                            "utf-8"
                          ),
                          "response_type" -> "code",
                          "scope" -> "snsapi_base",
                          "state" -> "wx7b168b095eb4090e"
                        ).map(i => s"${i._1}=${i._2}")
                          .mkString("&")
                        redirect(
                          s"https://open.weixin.qq.com/connect/oauth2/authorize?${params}#wechat_redirect",
                          StatusCodes.PermanentRedirect
                        )
                    }
                }
            }
          },
          get {
            path("into" / "from" / Segment / Segment) {
              (appid: String, ccode: String) =>
                extractClientIP {
                  ip =>
                    extractRequest {
                      request =>
                        val scheme: String = request.headers
                          .map(i => i.name() -> i.value())
                          .toMap
                          .getOrElse("X-Scheme", request.uri.scheme)

                        val (province, city) =
                          IpUtils.convertIpToProvinceCity(ip.getIp())
                        logger.info(
                          Map(
                            "time" -> System.currentTimeMillis(),
                            "data" -> Map(
                              "event" -> LogEventKey.fromCcode,
                              "appid" -> appid,
                              "ccode" -> ccode,
                              "ip" -> ip.getIp(),
                              "province" -> province,
                              "city" -> city
                            )
                          ).toJson
                        )
                        val params: String = Map(
                          "appid" -> appid,
                          "redirect_uri" -> URLEncoder.encode(
                            (scheme + "://" + domain) + s"/pages/douyin/index?ccode=${ccode}&platform=douyin&appid=${appid}",
                            "utf-8"
                          ),
                          "response_type" -> "code",
                          "scope" -> "snsapi_base",
                          "state" -> appid
                        ).map(i => s"${i._1}=${i._2}")
                          .mkString("&")
                        redirect(
                          s"https://open.weixin.qq.com/connect/oauth2/authorize?${params}#wechat_redirect",
                          StatusCodes.PermanentRedirect
                        )
                    }
                }
            }
          },
          post {
            path("pay" / Segment / Segment) {
              (appid,orderId) =>
                entity(as[NodeSeq]) {
                  data =>
                    val message = WechatModel.WechatMessage.fromXml(appid, data)
                    logger.info(
                      Map(
                        "time" -> System.currentTimeMillis(),
                        "data" -> Map(
                          "event" -> LogEventKey.wechatMessage,
                          "appid" -> appid,
                          "appname" -> config.getString(s"wechat.${appid}.name"),
                          "message" -> message
                        )
                      ).toJson
                    )
                    val result = Source
                      .single(message)
                      .via(WechatStream.notifyMessage())
                      .runWith(Sink.head)
                    complete(result)
                }
            }
          },
          post {
            path("auth" / Segment) {
              appid =>
                entity(as[NodeSeq]) {
                  data =>
                    val message = WechatModel.WechatMessage.fromXml(appid, data)
                    logger.info(
                      Map(
                        "time" -> System.currentTimeMillis(),
                        "data" -> Map(
                          "event" -> LogEventKey.wechatMessage,
                          "appid" -> appid,
                          "appname" -> config.getString(s"wechat.${appid}.name"),
                          "message" -> message
                        )
                      ).toJson
                    )
                    val result = Source
                      .single(message)
                      .via(WechatStream.notifyMessage())
                      .runWith(Sink.head)
                    complete(result)
                }
            }
          },
          get {
            path("auth" / Segment) {
              appid =>
                parameters("signature", "timestamp", "nonce", "echostr") {
                  (
                      signature: String,
                      timestamp: String,
                      nonce: String,
                      echostr: String
                  ) =>
                    {
                      val auth = config.getString(s"wechat.${appid}.auth")
                      val sortArray: String =
                        Array(
                          auth,
                          timestamp,
                          nonce
                        ).sorted.mkString("")
                      if (
                        signature == DigestUtils.sha1Hex(sortArray.mkString(""))
                      ) {
                        complete(
                          HttpResponse(
                            StatusCodes.OK,
                            entity = HttpEntity(
                              ContentTypes.`text/plain(UTF-8)`,
                              echostr
                            )
                          )
                        )
                      } else {
                        fail("fail")
                      }
                    }
                }
            }
          }
        )
      }
    }
}
