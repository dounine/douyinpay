package com.dounine.douyinpay.router.routers

import akka.{NotUsed, actor}
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  ContentType,
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes
}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.{Directive1, Route, ValidationRejection}
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
  CoreEngine,
  LoginScanBehavior,
  OrderSources,
  QrcodeSources
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
  OrderStream,
  UserService,
  UserStream
}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.util.{MD5Util, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}

import java.nio.file.{Files, Paths}
import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}
class OrderRouter(system: ActorSystem[_]) extends SuportRouter {

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
  val noSign = system.settings.config.getBoolean("app.noSign")

  def userInfo()(implicit
      system: ActorSystem[_]
  ): Directive1[UserModel.DbInfo] = {
    parameters("apiKey".as[String].optional).flatMap {
      case Some(apiKey) =>
        onComplete(UserStream.source(apiKey, system).runWith(Sink.head))
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
              val future = http
                .singleRequest(
                  request = HttpRequest(
                    uri =
                      s"https://webcast.amemv.com/webcast/user/open_info/?search_ids=${id}&aid=1128&source=1a0deeb4c56147d0f844d473b325a28b&fp=verify_khq5h2bx_oY8iEaW1_b0Yt_4Hvt_9PRa_3U70XFUYPgzI&t=${System
                        .currentTimeMillis()}",
                    method = HttpMethods.GET
                  ),
                  settings = ConnectSettings.httpSettings(system)
                )
                .flatMap {
                  case HttpResponse(_, _, entity, _) =>
                    entity.dataBytes
                      .runFold(ByteString(""))(_ ++ _)
                      .map(_.utf8String)
                      .map(_.jsonTo[PayUserInfoModel.DouYinSearchResponse])
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
                  case msg @ _ =>
                    logger.error(s"请求失败 $msg")
                    Future.failed(new Exception(s"请求失败 $msg"))
                }
              onComplete(future) {
                case Success(value)     => ok(value)
                case Failure(exception) => fail(exception.getLocalizedMessage)
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
                  money = data.money.toInt,
                  volumn = data.money.toInt * 10,
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
                              QrcodeSources.typeKey,
                              QrcodeSources.typeKey.name
                            )
                            .ask(
                              QrcodeSources.CreateOrder(
                                order
                              )
                            )(10.seconds)
                            .map {
                              case QrcodeSources
                                    .CreateOrderOk(request, qrcode) =>
                                ok(
                                  Map(
                                    "dbQuery" -> (config.getString(
                                      "file.domain"
                                    ) + s"/order/info/" + order.orderId),
                                    "qrcode" -> (config.getString(
                                      "file.domain"
                                    ) + "/file/image?path=" + qrcode)
                                  )
                                )
                              case QrcodeSources
                                    .CreateOrderFail(request, error) =>
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
        }
      }
    )

}
