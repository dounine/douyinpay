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
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.dounine.douyinpay.model.models.{
  AccountModel,
  BaseSerializer,
  RouterModel,
  WechatModel
}
import com.dounine.douyinpay.service.{AccountStream, CardStream, WechatStream}
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.{Logger, LoggerFactory}

import java.net.URLEncoder
import java.util.UUID
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
import scala.xml.NodeSeq

class CardRouter()(implicit system: ActorSystem[_])
    extends SuportRouter
    with ScalaXmlSupport {

  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[CardRouter])
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

  val appid = config.getString("wechat.appid")
  val domain = config.getString("file.domain")
  val routerProfix = config.getString("routerPrefix")
  val http = Http(system)

  val route: Route =
    cors() {
      pathPrefix("card") {
        concat(
          get {
            path("add" / IntNumber) { money =>
              {
                complete(
                  Source
                    .single(money)
                    .via(CardStream.createCard())
                    .map(cardId => {
                      RouterModel.Data(
                        Option(
                          Map(
                            "card" -> cardId
                          )
                        )
                      )
                    })
                )
              }
            }
          },
          get {
            path("card" / "active" / Segment) {
              card =>
                val params: String = Map(
                  "appid" -> appid,
                  "redirect_uri" -> URLEncoder.encode(
                    domain + s"?card=${card}",
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
        )
      }
    }
}
