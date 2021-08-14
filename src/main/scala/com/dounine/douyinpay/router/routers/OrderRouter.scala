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
import com.dounine.douyinpay.tools.util.{MD5Util, Request, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._

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
            } ~ path("pay" / "user" / "info" / "douyin" / Segment) {
              id =>
                auth {
                  session =>
                    {
                      logger.info(
                        Map(
                          "time" -> System.currentTimeMillis(),
                          "data" -> Map(
                            "event" -> LogEventKey.userInfoQuery,
                            "userAccount" -> id,
                            "openid" -> session.openid,
                            "ip" -> ip.getIp()
                          )
                        ).toJson
                      )
                      cache(lfuCache, keyFunction) {
                        if (id.contains("抖音")) {
                          fail("帐号格式不正确噢")
                        } else {
                          val future = Request
                            .get[PayUserInfoModel.DouYinSearchResponse](
                              s"https://webcast.amemv.com/webcast/user/open_info/?search_ids=${id}&aid=1128&source=1a0deeb4c56147d0f844d473b325a28b&fp=verify_khq5h2bx_oY8iEaW1_b0Yt_4Hvt_9PRa_3U70XFUYPgzI&t=${System
                                .currentTimeMillis()}"
                            )
                            .map(item => {
                              if (item.data.open_info.nonEmpty) {
                                val data
                                    : PayUserInfoModel.DouYinSearchOpenInfo =
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
                            case Success(
                                  value: Option[PayUserInfoModel.Info]
                                ) =>
                              value match {
                                case Some(value) => ok(value)
                                case None        => fail("用户不存在")
                              }
                            case Failure(exception) =>
                              fail(exception.getLocalizedMessage)
                          }
                        }
                      }
                    }
                }
            } ~ path("pay" / "money" / "info" / "douyin") {
              auth { session =>
                {
                  logger.info(
                    Map(
                      "time" -> System.currentTimeMillis(),
                      "data" -> Map(
                        "event" -> LogEventKey.payMoneyMenu,
                        "openid" -> session.openid,
                        "ip" -> ip.getIp()
                      )
                    ).toJson
                  )
                  ok(
                    moneys.map(i => {
                      Map(
                        "money" -> s"¥${i._1}",
                        "volumn" -> i._2
                      )
                    })
                  )
                }
              }
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
            path("order" / "update") {
              entity(asSourceOf[OrderModel.UpdateStatus]) {
                source =>
                  val result = source
                    .map(data => {
                      logger.info(
                        Map(
                          "time" -> System.currentTimeMillis(),
                          "data" -> Map(
                            "event" -> (if (data.pay) LogEventKey.orderPayOk
                                        else LogEventKey.orderPayFail),
                            "payOrderId" -> data.orderId
                          )
                        ).toJson
                      )
                      data
                    })
                    .via(OrderStream.updateOrderStatus())
                    .map(_._1.orderId)
                    .via(OrderStream.queryOrder())
                    .via(OrderStream.notifyOrderPayStatus())
                    .map(r => okData(r))
                  complete(
                    result
                  )
              }
            } ~
              path("pay" / "qrcode") {
                auth {
                  session =>
                    entity(asSourceOf[OrderModel.Recharge]) {
                      source =>
                        {
                          val result = source
                            .map(_ -> session.openid)
                            .map(i => {
                              if (
                                MD5Util.md5(
                                  Array(
                                    i._1.id,
                                    i._1.money,
                                    i._1.volumn,
                                    session.openid
                                  ).sorted.mkString("")
                                ) != i._1.sign
                              ) {
                                logger.error(
                                  Map(
                                    "time" -> System.currentTimeMillis(),
                                    "data" -> Map(
                                      "event" -> LogEventKey.orderCreateSignError,
                                      "openid" -> session.openid,
                                      "payAccount" -> i._1.id,
                                      "payMoney" -> i._1.money,
                                      "payVolumn" -> i._1.volumn,
                                      "sign" -> i._1.sign,
                                      "ip" -> ip.getIp()
                                    )
                                  ).toJson
                                )
                                throw new Exception("创建定单验证不通过")
                              }
                              i
                            })
                            .via(WechatStream.userInfoQuery2())
                            .map(tp2 => {
                              val data = tp2._1
                              val orderId = UUID
                                .randomUUID()
                                .toString
                                .replaceAll("-", "")
                              val money = data.money
                                .replaceAll("¥", "")
                                .replaceAll(",", "")
                                .toDouble
                                .toInt
                              logger.info(
                                Map(
                                  "time" -> System.currentTimeMillis(),
                                  "data" -> Map(
                                    "event" -> LogEventKey.orderCreateRequest,
                                    "openid" -> session.openid,
                                    "payAccount" -> tp2._1.id,
                                    "payMoney" -> money,
                                    "payOrderId" -> orderId,
                                    "ip" -> ip.getIp()
                                  )
                                ).toJson
                              )
                              val userInfo = tp2._2
                              OrderModel.DbInfo(
                                orderId = orderId,
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
                                fee = BigDecimal("0.00"),
                                platform = PayPlatform.douyin,
                                createTime = LocalDateTime.now(),
                                payCount = 0,
                                payMoney = 0
                              )
                            })
                            .via(OrderStream.add())
                            .map(_._1)
                            .via(OrderStream.qrcode())
                            .via(OrderStream.notifyOrderCreateStatus())
                            .map(i => {
                              if (i._2.qrcode.isEmpty) {
                                logger.error(
                                  Map(
                                    "time" -> System.currentTimeMillis(),
                                    "data" -> Map(
                                      "event" -> LogEventKey.orderCreateFail,
                                      "openid" -> session.openid,
                                      "payAccount" -> i._1.id,
                                      "payMoney" -> i._1.money,
                                      "payOrderId" -> i._1.orderId,
                                      "payQrcode" -> "",
                                      "payMessage" -> i._2.message.getOrElse(
                                        ""
                                      ),
                                      "paySetup" -> i._2.setup.getOrElse(""),
                                      "ip" -> ip.getIp()
                                    )
                                  ).toJson
                                )
                                throw new Exception(
                                  s"${i._1.orderId} qrcode is empty"
                                )
                              } else {
                                logger.info(
                                  Map(
                                    "time" -> System.currentTimeMillis(),
                                    "data" -> Map(
                                      "event" -> LogEventKey.wechatLogin,
                                      "openid" -> session.openid,
                                      "payAccount" -> i._1.id,
                                      "payMoney" -> i._1.money,
                                      "payOrderId" -> i._1.orderId,
                                      "payQrcode" -> i._2.qrcode.getOrElse(
                                        ""
                                      ),
                                      "ip" -> ip.getIp()
                                    )
                                  ).toJson
                                )
                              }
                              i
                            })
                            .map(t => (t._1, t._2.qrcode.get))
                            .via(OrderStream.downloadQrocdeFile())
                            .idleTimeout(15.seconds)
                            .map((result: (OrderModel.DbInfo, String)) => {
                              okData(
                                Map(
                                  "dbQuery" -> (config.getString(
                                    "file.domain"
                                  ) + s"/${routerPrefix}/order/info/" + result._1.orderId),
                                  "qrcode" -> (config.getString(
                                    "file.domain"
                                  ) + s"/${routerPrefix}/file/image?path=" + result._2)
                                )
                              )
                            })
                            .recover { e =>
                              {
                                e.printStackTrace()
                                failMsg("当前充值人数太多、请稍候再试")
                              }
                            }

                          complete(result)
                        }
                    }
                }
              }
          }
        )
      }
    }

}
