package com.dounine.douyinpay.router.routers

import akka.actor
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.directives.CachingDirectives.cache
import akka.http.scaladsl.server._
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.util.{ByteString, Timeout}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.dounine.douyinpay.behaviors.cache.ReplicatedCacheBehavior
import com.dounine.douyinpay.behaviors.engine.{LoginScanBehavior, QrcodeSources}
import com.dounine.douyinpay.model.models.{
  BaseSerializer,
  OrderModel,
  PayUserInfoModel,
  RouterModel,
  UserModel
}
import com.dounine.douyinpay.service.{
  OrderService,
  UserService,
  UserStream,
  WechatStream
}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.util.ServiceSingleton
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import java.util.UUID
import javax.xml.bind.DatatypeConverter
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

class WechatRouter(system: ActorSystem[_]) extends SuportRouter {

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
        post{
          path("wechat"/"auth"){
            println("come in")
            ok("hello")
          }
        }
        ,get {
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
