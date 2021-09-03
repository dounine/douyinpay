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
import akka.stream.scaladsl.Source
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.dounine.douyinpay.model.models.{
  BaseSerializer,
  PhoneModel,
  RouterModel
}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URLEncoder
import scala.concurrent.{ExecutionContextExecutor, duration}
import scala.concurrent.duration._

class PhoneRouter()(implicit system: ActorSystem[_])
    extends SuportRouter
    with ScalaXmlSupport {

  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[PhoneRouter])
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
      pathPrefix("phone") {
        concat(
          post {
            path("notification") {
              entity(as[PhoneModel.PhoneNotification]) { data =>
                logger.info("come in {}", data)
                ok(data)
              }
            }
          }
        )
      }
    }
}
