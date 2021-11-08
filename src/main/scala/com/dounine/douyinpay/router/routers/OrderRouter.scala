package com.dounine.douyinpay.router.routers

import akka.{NotUsed, actor}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.`User-Agent`
import akka.http.scaladsl.model.{ContentType, ContentTypes, HttpEntity, HttpHeader, HttpMethods, HttpProtocol, HttpRequest, HttpResponse, MediaTypes, RemoteAddress, StatusCode, Uri}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.directives.CachingDirectives.cache
import akka.http.scaladsl.server.{Directive1, RequestContext, Route, RouteResult, ValidationRejection}
import akka.stream._
import akka.stream.scaladsl.{Concat, Flow, GraphDSL, Keep, Merge, Partition, Sink, Source}
import akka.util.{ByteString, Timeout}
import com.dounine.douyinpay.behaviors.cache.ReplicatedCacheBehavior
import com.dounine.douyinpay.model.models.OrderModel.FutureCreateInfo
import com.dounine.douyinpay.model.models.RouterModel.JsonData
import com.dounine.douyinpay.model.models.{AccountModel, BaseSerializer, OrderModel, PayUserInfoModel, RouterModel, UserModel}
import com.dounine.douyinpay.model.types.service.{LogEventKey, MechinePayStatus, PayPlatform, PayStatus}
import com.dounine.douyinpay.service.{AccountStream, OpenidStream, OrderService, OrderStream, PayStream, UserService, UserStream, WechatStream}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.util.{IpUtils, MD5Util, OpenidPaySuccess, Request, ServiceSingleton, UUIDUtil}
import org.slf4j.{Logger, LoggerFactory}
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.dounine.douyinpay.router.routers.errors.{InvalidException, PayManyException}
import com.dounine.douyinpay.tools.akka.cache.CacheSource

import java.net.InetAddress
import java.nio.file.{Files, Paths}
import java.text.DecimalFormat
import java.time.{LocalDate, LocalDateTime}
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

  val moneyFormat = new DecimalFormat("###,###")
  val route: Route =
    cors() {
      extractClientIP { ip: RemoteAddress =>
        concat(
          get {
            path("pay" / "today" / "sum") {
              val result = OrderStream
                .queryTodayPaySum()
                .zip(
                  PayStream.queryTodayPaySum()
                )
                .zipWith(
                  PayStream.queryTodayNewUserPay()
                )((sum,next)=> (sum._1,sum._2,next))
                .zipWith(
                  OrderStream.queryTodayNewUserPay()
                )((sum,next) => (sum._1,sum._2,sum._3,next))
                .map {
                  case (payInfo, rechargeInfo,rechargeNewUsers,registerPayOrders) => {
                    HttpResponse(
                      entity = HttpEntity(
                        ContentTypes.`text/html(UTF-8)`,
                        s"""
                          |<html lang="zh-CN">
                          |<head>
                          |    <meta charset="UTF-8">
                          |</head>
                          |<body>
                          |<div>
                          |<li>充值金额:	${moneyFormat.format(payInfo._2.payMoney)}   <-->   ${moneyFormat.format(payInfo._1.payMoney)}</li>
                          |<li>新充金额:  ${moneyFormat.format(registerPayOrders._2.map(_.payMoney).sum)}   <-->   ${moneyFormat.format(registerPayOrders._1.map(_.payMoney).sum)}
                          |<li>新存金额:  ${moneyFormat.format(rechargeNewUsers._2.map(_.payMoney).sum/100)}   <-->   ${moneyFormat.format(rechargeNewUsers._1.map(_.payMoney).sum/100)}
                          |<li>预存金额:	${moneyFormat.format(rechargeInfo._2.payedMoney)}   <-->   ${moneyFormat.format(rechargeInfo._1.payedMoney)}</li>
                          |<li>退款金额:	${moneyFormat.format(rechargeInfo._2.refundMoney)}   <-->   ${moneyFormat.format(rechargeInfo._1.refundMoney)}</li>
                          |<li>失败金额:	${moneyFormat.format(payInfo._2.noPayMoney)}   <-->   ${moneyFormat.format(payInfo._1.noPayMoney)}</li>
                          |<li>---------</li>
                          |<li>充值人数:	${moneyFormat.format(payInfo._2.payPeople)}   <-->   ${moneyFormat.format(payInfo._1.payPeople)}</li>
                          |<li>新充人数:	${moneyFormat.format(registerPayOrders._2.map(_.openid).size)}   <-->   ${moneyFormat.format(registerPayOrders._1.map(_.openid).size)}</li>
                          |<li>新存人数:	${moneyFormat.format(rechargeNewUsers._2.map(_.openid).size)}   <-->   ${moneyFormat.format(rechargeNewUsers._1.map(_.openid).size)}</li>
                          |<li>预存人数:	${moneyFormat.format(rechargeInfo._2.payedPeople)}   <-->   ${moneyFormat.format(rechargeInfo._1.payedPeople)}</li>
                          |<li>退款人数:	${moneyFormat.format(rechargeInfo._2.refundPeople)}   <-->   ${moneyFormat.format(rechargeInfo._1.refundPeople)}</li>
                          |<li>失败人数:	${moneyFormat.format(payInfo._2.noPayPeople)}   <-->   ${moneyFormat.format(payInfo._1.noPayPeople)}</li>
                          |<li>---------</li>
                          |<li>充值次数:	${moneyFormat.format(payInfo._2.payCount)}   <-->    ${moneyFormat.format(payInfo._1.payCount)}</li>
                          |<li>新充次数:	${moneyFormat.format(registerPayOrders._2.map(_.payCount).sum)}   <-->    ${moneyFormat.format(registerPayOrders._1.map(_.payCount).sum)}</li>
                          |<li>新存次数:	${moneyFormat.format(rechargeNewUsers._2.map(_.payCount).sum)}   <-->    ${moneyFormat.format(rechargeNewUsers._1.map(_.payCount).sum)}</li>
                          |<li>预存次数:	${moneyFormat.format(rechargeInfo._2.payedCount)}   <-->   ${moneyFormat.format(rechargeInfo._1.payedCount)}</li>
                          |<li>退款次数:	${moneyFormat.format(rechargeInfo._2.refundCount)}   <-->   ${moneyFormat.format(rechargeInfo._1.refundCount)}</li>
                          |<li>失败次数:	${moneyFormat.format(payInfo._2.noPayCount)}   <-->   ${moneyFormat.format(payInfo._1.noPayCount)}</li>
                          |</div>
                          |</body>
                          |</html>
                          |""".stripMargin
                      )
                    )
                  }
                }
                .runWith(Sink.head)

              complete(
                result
              )
            } ~ path("kuaishou" / "url") {
              parameters("shortUrl") {
                shortUrl: String =>
                  val result = http
                    .singleRequest(
                      HttpRequest(
                        uri = shortUrl,
                        headers = Seq(
                          `User-Agent`(
                            "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1 wechatdevtools/1.05.2107221 MicroMessenger/8.0.5 Language/zh_CN webview/16314653301349493"
                          )
                        )
                      )
                    )
                    .map {
                      case HttpResponse(
                            code: StatusCode,
                            value: Seq[HttpHeader],
                            entity,
                            protocol: HttpProtocol
                          ) =>
                        value
                          .find(_.name().equalsIgnoreCase("Location")) match {
                          case Some(value) => Some(value.value())
                          case None        => None
                        }
                    }(system.executionContext)
                    .map(result => {
                      okData(result.getOrElse(""))
                    })
                  complete(result)
              }
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
                          "orderId" -> orderId,
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
                    .mapAsync(1) { update =>
                      CacheSource(system)
                        .cache()
                        .remove("createOrder_" + update.order.openid)
                        .map(_ => update)
                    }
                    .map(data => {
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
                      data
                    })
                    .flatMapConcat(i => {
                      if (i.pay) {
                        Source
                          .single(i.order.openid)
                          .via(
                            OrderStream.queryOpenidTodayPay()
                          )
                          .zip(
                            Source
                              .single(i.order.openid)
                              .via(AccountStream.query())
                              .zip(
                                Source
                                  .single(i.order.openid)
                                  .via(OrderStream.queryOpenidSharedPay())
                              )
                          )
                          .zipWith(
                            Source
                              .single(i.order.openid)
                              .via(OpenidStream.query())
                          )((pre, next) => (pre._1, pre._2._1, pre._2._2, next))
                          .flatMapConcat {
                            case (
                                  todayPayList,
                                  maybeInfo,
                                  sharePayedMoney,
                                  wechatUser
                                ) =>
                              val commonRemain: Int =
                                (100 + sharePayedMoney.getOrElse(
                                  0
                                ) / 2) - todayPayList
                                  .map(_.money)
                                  .sum
                              val todayRemain: Double =
                                if (commonRemain < 0) 0d
                                else commonRemain * 0.02
                              val payInfo = OpenidPaySuccess
                                .query(i.order.openid)
                              val admins = system.settings.config.getStringList("admins")
                              if(admins.contains(i.order.openid)){
                                Source.single(i)
                              }else if (
                                LocalDate
                                  .now()
                                  .atStartOfDay()
                                  .isAfter(
                                    wechatUser.get.createTime
                                      .plusDays(1)
                                  ) &&
                                payInfo.count > 2 && payInfo.money > 100
                              ) {
                                if (
                                  todayPayList
                                    .map(_.money)
                                    .sum + i.order.money <= 100
                                ) {
                                  Source.single(i)
                                } else if (
                                  ((maybeInfo
                                    .map(_.money)
                                    .getOrElse(
                                      0
                                    ) + (todayRemain * 100)) - i.order.money * 100 * 0.02) < 0
                                ) {
                                  logger.error(
                                    "非法支付、余额不足、registerTime->{}, balance->{}, order->{}, todayPaySum->{}",
                                    wechatUser.get.createTime,
                                    maybeInfo.map(_.money).getOrElse(0),
                                    i.order,
                                    todayPayList.map(_.money).sum
                                  )
                                  throw InvalidException("非法支付、余额不足")
                                } else {
                                  Source
                                    .single(
                                      AccountModel.AccountInfo(
                                        openid = i.order.openid,
                                        money =
                                          (i.order.money * 100 * 0.02).toInt - (todayRemain * 100).toInt
                                      )
                                    )
                                    .via(
                                      AccountStream.decrmentMoneyToAccount()
                                    )
                                    .map(_ => i)
                                }
                              } else {
                                Source.single(i)
                              }
                          }
                      } else Source.single(i)
                    })
                    .map(data => {
                      if (data.pay) {
                        OpenidPaySuccess.add(
                          data.order.openid,
                          data.order.money
                        )
                      }
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
