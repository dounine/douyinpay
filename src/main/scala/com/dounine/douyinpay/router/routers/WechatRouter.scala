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
import com.dounine.douyinpay.model.models.{BaseSerializer, WechatModel}
import com.dounine.douyinpay.service.WechatStream
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.{Logger, LoggerFactory}

import java.util.UUID
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.xml.NodeSeq

class WechatRouter(system: ActorSystem[_])
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

  val http = Http(system)

  val route: Route =
    cors() {
      concat(
        get {
          path("wechat" / "web" / "user" / "login" / Segment) { code =>
            {
              val result = Source
                .single(code)
                .via(WechatStream.webBaseUserInfo(system))
              complete(result)
            }
          }
        },
        get {
          path("wechat" / "jsapi" / "signature") {
            parameters("url".as[String]) {
              url =>
                {
                  val info = Map(
                    "noncestr" -> UUID
                      .randomUUID()
                      .toString
                      .replaceAll("-", ""),
                    "timestamp" -> System.currentTimeMillis() / 1000,
                    "url" -> url
                  )
                  val result = WechatStream
                    .jsapiQuery(system)
                    .map(ticket => {
                      info ++ Map(
                        "jsapi_ticket" -> ticket
                      )
                    })
                    .map(result => {
                      result
                        .map(i => s"${i._1}=${i._2}")
                        .toSeq
                        .sorted
                        .mkString("&")
                    })
                    .map(DigestUtils.sha1Hex)
                    .map(signature => {
                      info.filterNot(_._1 == "url") ++ Map(
                        "signature" -> signature
                      )
                    })
                    .map(okData)

                  complete(result)
                }
            }
          }
        },
        post {
          path("wechat" / "auth") {
            entity(as[NodeSeq]) { data =>
              try {
                val message = WechatModel.WechatMessage.fromXml(data)
                logger.info(
                  message.toJson.jsonTo[Map[String, Any]].mkString("\n")
                )
                val result = Source
                  .single(message)
                  .via(WechatStream.notifyMessage(system))
                  .runWith(Sink.head)
                complete(result)
              }
            }
          }
        },
        get {
          path("wechat" / "auth") {
            parameters("signature", "timestamp", "nonce", "echostr") {
              (
                  signature: String,
                  timestamp: String,
                  nonce: String,
                  echostr: String
              ) =>
                {
                  logger.info("hello")
                  val sortArray: String =
                    Array(
                      config.getString("wechat.auth"),
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
