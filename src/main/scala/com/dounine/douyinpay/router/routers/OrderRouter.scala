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
import com.dounine.douyinpay.behaviors.engine.{
  LoginScanBehavior,
  QrcodeBehavior
}
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
  MechinePayStatus,
  PayPlatform,
  PayStatus
}
import com.dounine.douyinpay.service.{
  OrderService,
  UserService,
  UserStream,
  WechatStream
}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.util.{MD5Util, Request, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._

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
    ("98.00", "980"),
    ("298.00", "2,980"),
    ("518.00", "5,180"),
    ("1,598.00", "15,980"),
    ("3,000.00", "30,000"),
    ("5,000.00", "50,000")
  )

  val http = Http(system)

  val route: Route =
    cors() {
      concat(
        get {
          path("user" / "login") {
            val actor = system.systemActorOf(
              LoginScanBehavior(),
              s"${UUID.randomUUID().toString}"
            )
            import akka.actor.typed.scaladsl.AskPattern._
            val future: Future[BaseSerializer] = actor.ask(
              LoginScanBehavior.GetScan()
            )(new Timeout(10.seconds), system.scheduler)
            onComplete(future) {
              case Failure(exception) => fail(exception.getMessage)
              case Success(value) => {
                value match {
                  case LoginScanBehavior.ScanSuccess(url) => {
                    complete(
                      HttpResponse(entity =
                        HttpEntity(
                          ContentType(MediaTypes.`image/png`),
                          Files.readAllBytes(Paths.get(url))
                        )
                      )
                    )
                  }
                  case LoginScanBehavior.ScanFail(msg) =>
                    fail(msg)
                }
              }
            }
          } ~ path("pay" / "user" / "info" / "douyin" / Segment) {
            id =>
              {
                cache(lfuCache, keyFunction) {
                  val future = Request
                    .get[PayUserInfoModel.DouYinSearchResponse](
                      s"https://webcast.amemv.com/webcast/user/open_info/?search_ids=${id}&aid=1128&source=1a0deeb4c56147d0f844d473b325a28b&fp=verify_khq5h2bx_oY8iEaW1_b0Yt_4Hvt_9PRa_3U70XFUYPgzI&t=${System
                        .currentTimeMillis()}"
                    )
                    .map(item => {
                      if (item.data.open_info.nonEmpty) {
                        val data: PayUserInfoModel.DouYinSearchOpenInfo =
                          item.data.open_info.head
                        Option(
                          PayUserInfoModel.Info(
                            nickName = data.nick_name,
                            id = data.search_id,
                            avatar = data.avatar_thumb.url_list.head
                          )
                        )
                      } else {
                        Option.empty
                      }
                    })
                  onComplete(future) {
                    case Success(value: Option[PayUserInfoModel.Info]) =>
                      value match {
                        case Some(value) => ok(value)
                        case None        => fail("用户不存在")
                      }
                    case Failure(exception) =>
                      fail(exception.getLocalizedMessage)
                  }
                }
              }
          } ~ path("pay" / "money" / "info" / "douyin") {
            ok(
              moneys.map(i => {
                Map(
                  "money" -> s"¥${i._1}",
                  "volumn" -> i._2
                )
              })
            )
          } ~ path("order" / "info" / Segment) { orderId =>
            {
              onComplete(orderService.queryOrderStatus(orderId)) {
                case Success(value)     => ok(value)
                case Failure(exception) => fail(exception.getMessage)
              }
            }
          }
        },
        post {
          path("pay" / "qrcode") {
            entity(as[OrderModel.Recharge]) {
              data =>
                {
                  logger.info(data.logJson)
                  val order = OrderModel.DbInfo(
                    orderId = UUID.randomUUID().toString.replaceAll("-", ""),
                    nickName = data.nickName,
                    pay = false,
                    expire = false,
                    openid = data.openid,
                    id = data.id,
                    money = data.money
                      .replaceAll("¥", "")
                      .replaceAll(",", "")
                      .toDouble
                      .toInt,
                    volumn = data.money
                      .replaceAll("¥", "")
                      .replaceAll(",", "")
                      .toDouble
                      .toInt * 10,
                    platform = data.platform,
                    createTime = LocalDateTime.now(),
                    payCount = 0,
                    payMoney = 0
                  )

                  val result =
                    orderService
                      .add(
                        order
                      )
                      .flatMap {
                        _ =>
                          {
                            sharding
                              .entityRefFor(
                                QrcodeBehavior.typeKey,
                                QrcodeBehavior.typeKey.name
                              )
                              .ask(
                                QrcodeBehavior.CreateOrder(
                                  order
                                )
                              )(15.seconds)
                              .map {
                                case QrcodeBehavior
                                      .CreateOrderOk(request, qrcode) =>
                                  ok(
                                    Map(
                                      "dbQuery" -> (config.getString(
                                        "file.domain"
                                      ) + s"/${routerPrefix}/order/info/" + order.orderId),
                                      "qrcode" -> (config.getString(
                                        "file.domain"
                                      ) + s"/${routerPrefix}/file/image?path=" + qrcode)
                                    )
                                  )
                                case QrcodeBehavior
                                      .CreateOrderFail(
                                        request,
                                        error,
                                        screen
                                      ) =>
                                  fail(
                                    error
                                  )
                              }
                          }
                      }
                  onComplete(result) {
                    case Failure(exception) => fail(exception.getMessage)
                    case Success(value)     => value
                  }
                }
            }
          } ~ path("pay" / "qrcode2" / "douyin") {
            auth {
              session =>
                {
                  entity(as[OrderModel.Recharge2]) {
                    data =>
                      {
                        val result = Source
                          .single(session.openid)
                          .via(WechatStream.userInfoQuery())
                          .map(userInfo => {
                            OrderModel.DbInfo(
                              orderId =
                                UUID.randomUUID().toString.replaceAll("-", ""),
                              nickName = userInfo.nickname,
                              pay = false,
                              expire = false,
                              openid = session.openid,
                              id = data.id,
                              money = data.money
                                .replaceAll("¥", "")
                                .replaceAll(",", "")
                                .toDouble
                                .toInt,
                              volumn = data.money
                                .replaceAll("¥", "")
                                .replaceAll(",", "")
                                .toDouble
                                .toInt * 10,
                              platform = PayPlatform.douyin,
                              createTime = LocalDateTime.now(),
                              payCount = 0,
                              payMoney = 0
                            )
                          })
                          .mapAsync(1)(order => {
                            orderService.add(order).map(_ => order)
                          })
                          .mapAsync(1)(order => {
                            sharding
                              .entityRefFor(
                                QrcodeBehavior.typeKey,
                                QrcodeBehavior.typeKey.name
                              )
                              .ask(
                                QrcodeBehavior.CreateOrder(
                                  order
                                )
                              )(15.seconds)
                              .map {
                                case QrcodeBehavior
                                      .CreateOrderOk(request, qrcode) =>
                                  ok(
                                    Map(
                                      "dbQuery" -> (config.getString(
                                        "file.domain"
                                      ) + s"/${routerPrefix}/order/info/" + order.orderId),
                                      "qrcode" -> (config.getString(
                                        "file.domain"
                                      ) + s"/${routerPrefix}/file/image?path=" + qrcode)
                                    )
                                  )
                                case QrcodeBehavior
                                      .CreateOrderFail(
                                        request,
                                        error,
                                        screen
                                      ) =>
                                  logger.error(
                                    "request -> {} , error -> {}",
                                    request,
                                    error
                                  )
                                  fail(
                                    "当前充值人数太多、请稍后再试"
                                  )
                              }
                          })
                          .runWith(Sink.head)

                        onComplete(result) {
                          case Failure(exception) => fail(exception.getMessage)
                          case Success(value)     => value
                        }
                      }
                  }
                }
            }
          }

        }
      )
    }

}
