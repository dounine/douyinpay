package com.dounine.douyinpay.behaviors.engine

import akka.{Done, NotUsed}
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.event.LogMarker
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.{
  Broadcast,
  DelayStrategy,
  Flow,
  GraphDSL,
  Keep,
  Merge,
  Partition,
  RunnableGraph,
  Sink,
  Source,
  Zip,
  ZipWith
}
import akka.stream.{
  Attributes,
  BoundedSourceQueue,
  ClosedShape,
  DelayOverflowStrategy,
  FlowShape,
  KillSwitches,
  QueueCompletionResult,
  QueueOfferResult,
  SourceShape,
  SystemMaterializer,
  UniqueKillSwitch
}
import com.dounine.douyinpay.model.models.{BaseSerializer, OrderModel}
import com.dounine.douyinpay.service.OrderService
import com.dounine.douyinpay.tools.akka.chrome.{Chrome, ChromePools}
import com.dounine.douyinpay.tools.json.{ActorSerializerSuport, JsonParse}
import com.dounine.douyinpay.tools.util.{DingDing, ServiceSingleton}
import org.openqa.selenium.{By, OutputType}
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.concurrent.Future
import scala.concurrent.duration._
object QrcodeSources extends ActorSerializerSuport {

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("QrcodeBehavior")

  private val logger = LoggerFactory.getLogger(QrcodeSources.getClass)
  case class AppInfo(
      appId: String,
      client: ActorRef[BaseSerializer],
      balance: BigDecimal
  ) extends BaseSerializer {
    override def hashCode(): Int = appId.hashCode

    override def equals(obj: Any): Boolean = {
      if (obj == null) {
        false
      } else {
        appId == obj.asInstanceOf[AppInfo].appId
      }
    }
  }

  sealed trait Event extends BaseSerializer

  case class CreateOrderPush(
      request: OrderSources.AppWorkPush,
      order: OrderModel.DbInfo
  ) extends Event

  case class CreateOrder(order: OrderModel.DbInfo)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Event

  case class CreateOrderOk(request: CreateOrder, qrcode: String) extends Event

  case class CreateOrderFail(request: CreateOrder, msg: String) extends Event

  case class PaySuccess(request: CreateOrder) extends Event

  case class PayFail(request: CreateOrder, msg: String) extends Event

  case class Shutdown()(val replyTo: ActorRef[Done]) extends Event

  implicit class FlowLog(data: Flow[Event, Event, NotUsed]) extends JsonParse {
    def log(): Flow[Event, Event, NotUsed] = {
      data
        .logWithMarker(
          s"qrcodeMarker",
          (e: Event) =>
            LogMarker(
              name = s"qrcodeMarker"
            ),
          (e: Event) => e.logJson
        )
        .withAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Info
          )
        )
    }
  }

  def apply(
      entityId: PersistenceId
  ): Behavior[BaseSerializer] =
    Behaviors.setup[BaseSerializer] { context: ActorContext[BaseSerializer] =>
      {
        implicit val materializer =
          SystemMaterializer(context.system).materializer
        implicit val ec = context.executionContext
        val chromeSize = context.system.settings.config.getInt("app.selenium.pool.minIdle")
        val orderService = ServiceSingleton.get(classOf[OrderService])
        val (
          chromeQueue: BoundedSourceQueue[Int],
          chromeSource: Source[Int, NotUsed]
        ) = Source
          .queue[Int](1)
          .watchTermination()((f, p) => {
            p.foreach(_ => logger.error("chromesource shutdown"))
            f
          })
          .preMaterialize()

        (0 to chromeSize).foreach(chromeQueue.offer)

        val sendNotifyMessage = (title: String, order: OrderModel.DbInfo) => {
          val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")
          DingDing.sendMessage(
            DingDing.MessageType.order,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = "定单通知",
                text = s"""
                          |## ${title}
                          | - nickName: ${order.nickName.getOrElse("")}
                          | - id: ${order.id}
                          | - money: ${order.money}
                          | - payCount: ${order.payCount}
                          | - payMoney: ${order.payMoney}
                          | - createTime: ${order.createTime.format(
                  timeFormatter
                )}
                          | - notifyTime: ${LocalDateTime
                  .now()
                  .format(timeFormatter)}
                          |""".stripMargin
              )
            ),
            context.system
          )
        }

        val coreFlow = createCoreFlow2(context.system)
        val notifyBeforeFlow = Flow[Event]
          .map {
            case r @ CreateOrder(order) => {
              sendNotifyMessage("定单创建", order)
              r
            }
          }
        val notifyAfterFlow = Flow[Event]
          .flatMapConcat {
            case r @ CreateOrderOk(request, qrcode) =>
              logger.info("create order ok -> {}", r)
              val order = r.request.order
              sendNotifyMessage("创建成功", order)
              request.replyTo.tell(r)
              Source.empty
            case r @ CreateOrderFail(_, _) =>
              r.request.replyTo.tell(r)
              chromeQueue.offer(1)
              val order = r.request.order
              sendNotifyMessage("创建失败", order)
              logger.error("create order fail -> {}", r)
              Source.single(r)
            case r @ PayFail(_, _) =>
              r.request.replyTo.tell(r)
              chromeQueue.offer(1)
              val order = r.request.order
              sendNotifyMessage("充值失败", order)
              logger.error("pay fail -> {}", r)
              Source.single(r)
            case r @ PaySuccess(request) =>
              val order = request.order
              val newRequest = PaySuccess(
                CreateOrder(
                  order.copy(
                    payCount = order.payCount + 1,
                    payMoney = order.payMoney + order.money
                  )
                )(request.replyTo)
              )
              r.request.replyTo.tell(r)
              chromeQueue.offer(1)
              sendNotifyMessage("充值成功", newRequest.request.order)
              logger.info("pay success -> {}", r)
              Source.single(newRequest)
          }

        val queryOrderFlow = Flow[Event]
          .mapAsync(4) {
            case r @ CreateOrder(order) => {
              orderService
                .queryIdInfo(order.id)
                .map(info => {
                  CreateOrder(
                    order.copy(
                      payCount = info._1,
                      payMoney = info._2
                    )
                  )(r.replyTo)
                })
            }
          }

        val updateOrderFlow = Flow[Event]
          .mapAsync(3) {
            case PaySuccess(request) =>
              orderService.updateAll(
                request.order.copy(
                  pay = true,
                  expire = true
                )
              )
            case PayFail(request, msg) =>
              orderService.updateAll(
                request.order.copy(
                  pay = false,
                  expire = true
                )
              )
            case CreateOrderFail(request, msg) =>
              orderService.updateAll(
                request.order.copy(
                  pay = false,
                  expire = true
                )
              )
          }

        val orderQueue = Source
          .queue[Event](1)
          .zipWith(chromeSource)((left: Event, _) => left)
          .filter {
            case r @ CreateOrder(order) => {
              if (
                java.time.Duration
                  .between(order.createTime, LocalDateTime.now())
                  .getSeconds < 3
              ) {
                true
              } else {
                logger.info("等待时间过长、回收")
                r.replyTo.tell(CreateOrderFail(r, "timeout"))
                chromeQueue.offer(1)
                false
              }
            }
          }
          .via(queryOrderFlow)
          .via(notifyBeforeFlow)
          .via(coreFlow)
          .via(notifyAfterFlow)
          .via(updateOrderFlow)
          .recover {
            case e => {
              logger.error(e.getMessage)
            }
          }
          .to(Sink.ignore)
          .run()

        Behaviors.receiveMessage[BaseSerializer] {
          case e @ CreateOrder(order) => {
            orderQueue.offer(e) match {
              case result: QueueCompletionResult =>
                logger.info("QueueCompletionResult")
                e.replyTo.tell(CreateOrderFail(e, "queue completion"))
              case QueueOfferResult.Enqueued =>
                logger.info("Enqueued")
              case QueueOfferResult.Dropped =>
                logger.info("Dropped")
                e.replyTo.tell(CreateOrderFail(e, "操作频繁、请稍后重试"))
            }
            Behaviors.same
          }
          case e @ Shutdown() => {
            logger.info("qrcode shutdown")
            e.replyTo.tell(Done)
            Behaviors.stopped
          }
        }
      }
    }

  def createCoreFlow2(
      system: ActorSystem[_]
  ): Flow[Event, Event, NotUsed] = {
    implicit val ec = system.executionContext
    Flow[Event]
      .log()
      .flatMapMerge(
        30,
        {
          case r @ CreateOrder(order) => {
            Source(iterable = 0 until ChromePools(system).poolSize())
              .flatMapMerge(
                10,
                id =>
                  createQrcodeSource(
                    system,
                    order,
                    id
                  )
              )
              .filter(_.isRight)
              .take(1)
              .orElse(Source.single(Left(new Exception("all fail"))))
              .flatMapConcat {
                case Left(error) =>
                  Source(
                    CreateOrderFail(
                      r,
                      error.getMessage
                    ) :: Nil
                  )
                case Right((chrome, order, qrcode, id)) =>
                  Source
                    .single(
                      CreateOrderOk(r, qrcode)
                    )
                    .merge(
                      createListenPay(
                        system,
                        chrome,
                        order,
                        id
                      ).map {
                        case Left(error) => PayFail(r, error.getMessage)
                        case Right(_)    => PaySuccess(r)
                      }
                    )
              }

          }
        }
      )
  }

//  def createCoreFlow(
//      system: ActorSystem[_]
//  ): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
//    implicit val ec = system.executionContext
//    Flow[BaseSerializer]
//      .collectType[Event]
//      .log()
//      .flatMapMerge(
//        30,
//        {
//          case r @ CreateOrderPush(request, order) => {
//            Source(0 until ChromePools(system).poolSize())
//              .flatMapMerge(
//                10,
//                id =>
//                  createQrcodeSource(
//                    system,
//                    order,
//                    id
//                  )
//              )
//              .filter(_.isRight)
//              .take(1)
//              .orElse(Source.single(Left(new Exception("all fail"))))
//              .flatMapMerge(
//                20,
//                {
//                  case Left(error) => {
//                    Source(
//                      OrderSources.PayError(
//                        r,
//                        error.getMessage
//                      ) :: ChromeSources
//                        .Finish(request.id) :: Nil
//                    )
//                  }
//                  case Right((chrome, order, qrcode, id)) =>
//                    (0 until ChromePools(system).poolSize())
//                      .filterNot(_ == id)
//                      .foreach(releaseId => {
//                        ChromePools(system).pool(releaseId).returnObject(chrome)
//                      })
//                    Source
//                      .single(
//                        OrderSources.PayPush(
//                          r,
//                          qrcode
//                        )
//                      )
//                      .merge(
//                        createListenPay(
//                          system,
//                          chrome,
//                          order,
//                          id
//                        ).flatMapMerge(
//                          10,
//                          {
//                            case Left(error) =>
//                              Source(
//                                OrderSources.PayError(
//                                  request = r,
//                                  error = error.getMessage
//                                ) :: ChromeSources.Finish(request.id)
//                                  :: Nil
//                              )
//                            case Right(value) =>
//                              Source(
//                                ChromeSources.Finish(request.id) :: OrderSources
//                                  .PaySuccess(
//                                    request = r
//                                  ) :: Nil
//                              )
//                          }
//                        )
//                      )
//                }
//              )
//          }
//          case ee => Source.single(ee)
//        }
//      )
//  }

  /**
    * 申请chrome浏览器
    * @param system actor
    * @return Source[Either[Throwable,ChromeResource]]
    */
  private def createChromeSource(
      system: ActorSystem[_],
      id: Int
  ): Source[Either[Throwable, Chrome], NotUsed] = {
    implicit val ec = system.executionContext
    import scala.concurrent.duration._
    Source
      .future {
        Future {
          ChromePools(system).pool(id).borrowObject()
        }
      }
      .map(Right.apply)
      .recover {
        case e: Throwable => {
          e.printStackTrace()
          Left(new Exception("chrome申请失败"))
        }
      }
  }

  /**
    * 获取支付二维码
    * @param system Actor system
    * @param order 定单
    * @return Left[Throwable] Right[source,order,qrcode]
    */
  def createQrcodeSource(
      system: ActorSystem[_],
      order: OrderModel.DbInfo,
      id: Int
  ): Source[
    Either[Throwable, (Chrome, OrderModel.DbInfo, String, Int)],
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    import scala.concurrent.duration._
    import scala.util.chaining._
    createChromeSource(system, id)
      .map {
        case Left(value) => {
          value.printStackTrace()
          throw value
        }
        case Right(value) => value
      }
      .flatMapConcat { source =>
        {
          Source
            .future(source.driver("douyin_cookie"))
            .mapAsync(1) { driver =>
              Future {
                logger.info("切换用户")
                driver.tap(_.findElementByClassName("btn").click())
              }.recover {
                case _ => throw new Exception("无法点击切换用户按钮")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("输入帐号")
                driver.tap(
                  _.findElementByTagName("input").sendKeys(order.id)
                )
              }.recover {
                case _ => throw new Exception("无法输入帐号")
              }
            }
            .mapAsync(1)(driver => {
              Future {
                logger.info("确认帐号")
                driver.tap(_.findElementByClassName("confirm-btn").click())
              }.recover {
                case _ => throw new Exception("无法点击确认帐号")
              }
            })
            .mapAsync(1) { driver =>
              Future {
                logger.info("点击自定义充值金额按钮")
                driver.tap(
                  _.findElementByClassName("customer-recharge").click()
                )
              }.recover {
                case _ => throw new Exception("无法点击自定义充值按钮")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("输入充值金额")
                driver.tap(
                  _.findElementByClassName("customer-recharge")
                    .findElement(By.tagName("input"))
                    .sendKeys(order.money.toString)
                )
              }.recover {
                case _ => throw new Exception("无法输入充值金额")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("点击支付")
                driver.tap(_.findElementByClassName("pay-button").click())
              }.recover {
                case _ => throw new Exception("无法点击支付按钮")
              }
            }
            .flatMapConcat { driver =>
              Source(1 to 4)
                .delayWith(
                  delayStrategySupplier = () =>
                    DelayStrategy.linearIncreasingDelay(
                      increaseStep = 200.milliseconds,
                      needsIncrease = _ => true
                    ),
                  overFlowStrategy = DelayOverflowStrategy.backpressure
                )
                .mapAsync(1) { _ =>
                  Future {
                    logger.info("查询二次确认框跟跳转")
                    if (driver.getCurrentUrl.contains("tp-pay.snssdk.com")) {
                      logger.info("已跳转")
                      Right(Right("已跳转"))
                    } else {
                      Right(
                        Left(
                          driver
                            .findElementByClassName("check-content")
                            .findElement(By.className("right"))
                            .click()
                        )
                      )
                    }
                  }.recover {
                    case _ => {
                      Left(new Exception("没有二次确认框也没跳转"))
                    }
                  }
                }
                .filter(_.isRight)
                .take(1)
                .orElse(Source.single(Left(new Exception("没有二次确认框也没跳转"))))
                .flatMapConcat {
                  case Left(error) => throw error
                  case Right(value) =>
                    value match {
                      case Left(clickSuccess) => {
                        logger.info("检查页面是否跳转")
                        Source(1 to 4)
                          .delayWith(
                            delayStrategySupplier = () =>
                              DelayStrategy.linearIncreasingDelay(
                                increaseStep = 200.milliseconds,
                                needsIncrease = _ => true
                              ),
                            overFlowStrategy =
                              DelayOverflowStrategy.backpressure
                          )
                          .map(_ => driver.getCurrentUrl)
                          .filter(_.contains("tp-pay.snssdk.com"))
                          .map(_ => Right(true))
                          .take(1)
                          .orElse(
                            Source.single(Left(new Exception("支付支付页面无法跳转")))
                          )
                      }
                      case Right(jumpPayPage) => Source.single(Right(true))
                    }
                }
                .mapAsync(1) {
                  case Left(error) => throw error
                  case Right(value) =>
                    Future {
                      logger.info("切换微信支付")
                      driver.tap(
                        _.findElementByClassName("pay-channel-wx")
                          .click()
                      )
                    }.recover {
                      case _ => throw new Exception("切换微信支付失败")
                    }
                }
                .flatMapConcat { driver =>
                  Source(1 to 3)
                    .delayWith(
                      delayStrategySupplier = () =>
                        DelayStrategy.linearIncreasingDelay(
                          increaseStep = 200.milliseconds,
                          needsIncrease = _ => true
                        ),
                      overFlowStrategy = DelayOverflowStrategy.backpressure
                    )
                    .mapAsync(1) { _ =>
                      Future {
                        logger.info("查找二维码图片")
                        Right(
                          driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                        )
                      }.recover {
                        case _ => Left(new Exception("支付二维找不到"))
                      }
                    }
                    .filter(_.isRight)
                    .take(1)
                    .orElse(Source.single(Left(new Exception("支付二维找不到"))))
                    .mapAsync(1) {
                      case Left(error) => throw error
                      case Right(_) =>
                        Future {
                          logger.info("二维码图片保存")
                          driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                            .getScreenshotAs(OutputType.FILE)
                        }.recover {
                          case e => {
                            logger.error(e.getMessage)
                            throw new Exception("二维码保存失败")
                          }
                        }
                    }
                }
            }
            .map(file => Right((source, order, file.getAbsolutePath, id)))
            .recover {
              case e: Throwable => {
                e.printStackTrace()
                ChromePools(system).pool(id).returnObject(source)
                Left(e)
              }
            }
        }
      }
      .recover {
        case e: Throwable => Left(e)
      }
  }

  /**
    * 监听用户是否支付
    * @param system Actor system
    * @param source chrome source
    * @param order order
    * @return Left[Throwable] Right[OrderModel.DbInfo]
    */
  def createListenPay(
      system: ActorSystem[_],
      chrome: Chrome,
      order: OrderModel.DbInfo,
      id: Int
  ): Source[Either[Throwable, OrderModel.DbInfo], NotUsed] = {
    implicit val ec = system.executionContext
    import scala.concurrent.duration._
    Source(1 to 60)
      .throttle(1, 1.seconds)
      .map(_ => {
        chrome.driver().getCurrentUrl
      })
      .filter(_.contains("result?app_id"))
      .map(_ => Right(order))
      .take(1)
      .orElse(Source.single(Left(new Exception("未支付"))))
      .recover {
        case e => {
          e.printStackTrace()
          Left(new Exception("未支付"))
        }
      }
      .watchTermination()((pv, future) => {
        future.foreach(_ => {
          ChromePools(system).pool(id).returnObject(chrome)
        })
        pv
      })
  }

}