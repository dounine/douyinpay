package com.dounine.douyinpay.router.routers

import akka.{NotUsed, actor}
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.{Directive1, Route, ValidationRejection}
import akka.stream._
import akka.stream.scaladsl.{Concat, Flow, GraphDSL, Keep, Merge, Partition, Sink, Source}
import akka.util.ByteString
import com.dounine.douyinpay.behaviors.cache.ReplicatedCacheBehavior
import com.dounine.douyinpay.behaviors.engine.{CoreEngine, OrderSources, QrcodeSources}
import com.dounine.douyinpay.model.models.OrderModel.FutureCreateInfo
import com.dounine.douyinpay.model.models.RouterModel.JsonData
import com.dounine.douyinpay.model.models.{BaseSerializer, OrderModel, RouterModel, UserModel}
import com.dounine.douyinpay.model.types.service.{MechinePayStatus, PayPlatform, PayStatus}
import com.dounine.douyinpay.service.{OrderService, OrderStream, UserService, UserStream}
import com.dounine.douyinpay.tools.util.{MD5Util, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}
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

  val route: Route =
    concat(
      get {
        path("user" / "info") {
          userInfo()(system) {
            userInfo =>
              {
                parameters(
                  "platform".as[String],
                  "userId".as[String],
                  "sign".as[String]
                ) {
                  (platform, userId, sign) =>
                    {

                      val pf = PayPlatform.withName(platform)
                      import akka.actor.typed.scaladsl.AskPattern._

                      require(
                        noSign || MD5Util.md5(
                          userInfo.apiSecret + userId + platform
                        ) == sign,
                        signInvalidMsg
                      )
                      val key = s"userInfo-${pf}-${userId}"

                      val result =
                        cacheBehavior
                          .ask(
                            ReplicatedCacheBehavior.GetCache(key)
                          )(3.seconds, system.scheduler)
                          .flatMap {
                            cacheValue =>
                              {
                                cacheValue.value match {
                                  case Some(value) =>
                                    Future.successful(
                                      value
                                        .asInstanceOf[Option[
                                          OrderModel.UserInfo
                                        ]]
                                    )
                                  case None =>
                                    orderService
                                      .userInfo(pf, userId)
                                      .map(result => {
                                        if (result.isDefined) {
                                          cacheBehavior.tell(
                                            ReplicatedCacheBehavior.PutCache(
                                              key = key,
                                              value = result,
                                              timeout = 1.days
                                            )
                                          )
                                        }
                                        result
                                      })
                                }
                              }
                          }
                          .map {
                            case Some(value) => okData(value)
                            case None        => failMsg("user not found")
                          }
                          .recover {
                            case ee => failMsg(ee.getMessage)
                          }
                      complete(result)
                    }
                }
              }
          }
        } ~
          path("balance") {
            parameterMap {
              querys =>
                {
                  logger.info(querys.logJson)
                  val queryInfo = querys.toJson.jsonTo[OrderModel.Balance]
                  val result = userService
                    .info(queryInfo.apiKey)
                    .map {
                      case Some(value) => {
                        require(
                          noSign || MD5Util.md5(
                            value.apiSecret
                          ) == queryInfo.sign,
                          signInvalidMsg
                        )
                        ok(
                          Map(
                            "balance" -> value.balance,
                            "margin" -> value.margin
                          )
                        )
                      }
                      case None => throw new Exception(apiKeyNotFound)
                    }
                  onComplete(result) {
                    case Failure(exception) => fail(exception.getMessage)
                    case Success(value)     => value
                  }
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
        path("order" / "recharge") {
          entity(as[OrderModel.Recharge]) {
            data =>
              {
                logger.info(data.logJson)
                val order = OrderModel.DbInfo(
                  orderId = UUID.randomUUID().toString,
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
                              case QrcodeSources.CreateOrderOk(request, qrcode) =>
                                ok(
                                  Map(
                                    "dbQuery" -> (config.getString("file.domain") + s"/order/info/" + order.orderId),
                                    "orderId" -> order.orderId,
                                    "qrcode" -> qrcode
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
