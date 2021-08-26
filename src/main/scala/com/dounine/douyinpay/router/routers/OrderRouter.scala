package com.dounine.douyinpay.router.routers

import akka.{NotUsed, actor}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  ContentType,
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes,
  RemoteAddress,
  Uri
}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.directives.CachingDirectives.cache
import akka.http.scaladsl.server.{
  Directive1,
  RequestContext,
  Route,
  RouteResult,
  ValidationRejection
}
import akka.stream._
import akka.stream.scaladsl.{
  Concat,
  Flow,
  GraphDSL,
  Keep,
  Merge,
  Partition,
  Sink,
  Source
}
import akka.util.{ByteString, Timeout}
import com.dounine.douyinpay.behaviors.cache.ReplicatedCacheBehavior
import com.dounine.douyinpay.model.models.OrderModel.FutureCreateInfo
import com.dounine.douyinpay.model.models.RouterModel.JsonData
import com.dounine.douyinpay.model.models.{
  BaseSerializer,
  OrderModel,
  PayUserInfoModel,
  RouterModel,
  UserModel
}
import com.dounine.douyinpay.model.types.service.{
  LogEventKey,
  MechinePayStatus,
  PayPlatform,
  PayStatus
}
import com.dounine.douyinpay.service.{
  OrderService,
  OrderStream,
  UserService,
  UserStream,
  WechatStream
}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.util.{
  IpUtils,
  MD5Util,
  OpenidPaySuccess,
  Request,
  ServiceSingleton,
  UUIDUtil
}
import org.slf4j.{Logger, LoggerFactory}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.dounine.douyinpay.router.routers.errors.PayManyException

import java.net.InetAddress
import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
class OrderRouter()(implicit system: ActorSystem[_]) extends SuportRouter {

  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[OrderRouter])
  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val actorSystem: actor.ActorSystem = materializer.system
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val config = system.settings.config.getConfig("app")

  val sharding = ClusterSharding(system)
  val orderService = ServiceSingleton.get(classOf[OrderService])
  val userService = ServiceSingleton.get(classOf[UserService])

  val moneyInValid = "充值金额只能是[6 ~ 5000]之间的整数"
  val signInvalidMsg = "sign验证失败"
  val apiKeyNotFound = "钥匙不存在"
  val orderNotFound = "定单不存在"
  val balanceNotEnough = "帐户可用余额不足"
  val orderAndOutOrderRequireOn = "[orderId/outOrder]不能全为空"
  val cacheBehavior = system.systemActorOf(ReplicatedCacheBehavior(), "cache")
  val noSign = config.getBoolean("noSign")
  val routerPrefix = config.getString("routerPrefix")

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

  val qrcodeLfuCacheSettings = defaultCachingSettings.lfuCacheSettings
    .withInitialCapacity(100)
    .withMaxCapacity(1000)
    .withTimeToLive(61.seconds)
    .withTimeToIdle(60.seconds)

  val qrcodeCachingSettings =
    defaultCachingSettings.withLfuCacheSettings(qrcodeLfuCacheSettings)
  val qrcodeLfuCache: Cache[String, LocalDateTime] = LfuCache(
    qrcodeCachingSettings
  )

  val auth = TokenAuth()

  def userInfo()(implicit
      system: ActorSystem[_]
  ): Directive1[UserModel.DbInfo] = {
    parameters("apiKey".as[String].optional).flatMap {
      case Some(apiKey) =>
        onComplete(UserStream.source(apiKey).runWith(Sink.head))
          .flatMap {
            case Failure(exception) =>
              reject(ValidationRejection("apiKey 不正确", Option(exception)))
            case Success(value) => provide(value)
          }
      case None => reject(ValidationRejection("apiKey 参数缺失"))
    }
  }

  val moneys = Array(
    ("6.00", "60"),
    ("30.00", "300"),
    ("66.00", "660"),
    ("88.00", "880"),
    ("288.00", "2,880"),
    ("688.00", "6,880"),
    ("1,888.00", "18,880"),
    ("6,666.00", "66,660"),
    ("8,888.00", "88,880")
  )

  val http = Http(system)

  val route: Route =
    cors() {
      extractClientIP { ip: RemoteAddress =>
        concat(
          get {
            path("pay" / "today" / "sum") {
              complete(
                OrderStream
                  .queryTodayPaySum()
                  .map(money => {
                    RouterModel.Data(
                      Some(
                        Map(
                          "昨天" -> money._1,
                          "今天" -> money._2
                        )
                      )
                    )
                  })
              )
            } ~ path("order" / "info" / Segment) {
              orderId =>
                auth {
                  session =>
                    val (province, city) =
                      IpUtils.convertIpToProvinceCity(ip.getIp())
                    logger.info(
                      Map(
                        "time" -> System.currentTimeMillis(),
                        "data" -> Map(
                          "event" -> LogEventKey.orderQuery,
                          "openid" -> session.openid,
                          "ip" -> ip.getIp(),
                          "province" -> province,
                          "city" -> city
                        )
                      ).toJson
                    )
                    onComplete(orderService.queryOrderStatus(orderId)) {
                      case Success(value)     => ok(Map("status" -> value))
                      case Failure(exception) => fail(exception.getMessage)
                    }
                }
            }
          },
          post {
            path("order" / "update") {
              entity(asSourceOf[OrderModel.UpdateStatus]) {
                source =>
                  val result = source
                    .map(data => {
                      qrcodeLfuCache.remove(data.order.openid)
                      logger.info(
                        Map(
                          "time" -> System.currentTimeMillis(),
                          "data" -> Map(
                            "event" -> (if (data.pay) LogEventKey.orderPayOk
                                        else LogEventKey.orderPayFail),
                            "order" -> data.order
                          )
                        ).toJson
                      )
                      OpenidPaySuccess.add(data.order.openid)
                      data
                    })
                    .via(OrderStream.updateOrderStatus())
                    .map(_._1.order.orderId)
                    .via(OrderStream.queryOrder())
                    .via(OrderStream.notifyOrderPayStatus())
                    .map(r => okData(r))
                  complete(
                    result
                  )
              }
            }
          }
        )
      }
    }

}
