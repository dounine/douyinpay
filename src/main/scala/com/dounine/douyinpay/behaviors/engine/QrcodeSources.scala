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
  RestartSource,
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
  RestartSettings,
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

  case class CreateOrderFail(
      request: CreateOrder,
      msg: String,
      screen: Option[String]
  ) extends Event

  case class PaySuccess(request: CreateOrder) extends Event

  case class PayFail(request: CreateOrder, msg: String, screen: Option[String])
      extends Event

  case class Shutdown()(val replyTo: ActorRef[Done]) extends Event

  case class IncrmentChrome() extends Event

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
        val config = context.system.settings.config.getConfig("app")
        val chromeSize =
          context.system.settings.config.getInt("app.selenium.pool.minIdle")
        val orderService = ServiceSingleton.get(classOf[OrderService])

        val sendNotifyMessage = (
            typ: DingDing.MessageType.MessageType,
            title: String,
            order: OrderModel.DbInfo,
            msg: Option[String],
            screen: Option[String]
        ) => {
          val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")
          val errorMsg = msg match {
            case Some(value) => s"\n - error: ${value}"
            case None        => ""
          }
          val errorScreen = screen match {
            case Some(screen) =>
              s"\n- 页面：![error](${config.getString("file.domain") + "/file/image?path=" + screen})"
            case None => ""
          }
          DingDing.sendMessage(
            typ,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = "定单通知",
                text = s"""
                          |## ${title}
                          | - nickName: ${order.nickName.getOrElse("")}
                          | - id: ${order.id}
                          | - money: ${order.money}
                          | - payCount: ${order.payCount}
                          | - payMoney: ${order.payMoney}${errorMsg}${errorScreen}
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

        val (orderQueue, orderSource) = Source
          .queue[Event](chromeSize)
          .preMaterialize()

        val coreFlow = createCoreFlow(context.system)
        val notifyBeforeFlow = Flow[Event]
          .map {
            case r @ CreateOrder(order) => {
              sendNotifyMessage(
                DingDing.MessageType.order,
                "定单创建",
                order,
                None,
                None
              )
              r
            }
          }
        val notifyAfterFlow = Flow[Event]
          .flatMapMerge(
            chromeSize,
            {
              case r @ CreateOrderOk(request, qrcode) =>
                logger.info(
                  "create order ok -> {}",
                  request.order.toJson.jsonTo[Map[String, Any]].mkString("\n")
                )
                val order = r.request.order
                sendNotifyMessage(
                  DingDing.MessageType.order,
                  "创建成功",
                  order,
                  None,
                  None
                )
                request.replyTo.tell(r)
                Source.empty
              case r @ CreateOrderFail(_, msg, screen) =>
//                orderQueue.offer(IncrmentChrome())
                r.request.replyTo.tell(r)
                val order = r.request.order
                sendNotifyMessage(
                  DingDing.MessageType.order,
                  "创建失败",
                  order,
                  Some(msg),
                  screen
                )
                logger.error(
                  "create order fail -> {} {}",
                  msg,
                  r.request.order.toJson.jsonTo[Map[String, Any]].mkString("\n")
                )
                Source.single(r)
              case r @ PayFail(_, msg, screen) =>
//                orderQueue.offer(IncrmentChrome())
                val order = r.request.order
                sendNotifyMessage(
                  DingDing.MessageType.payerr,
                  "充值失败",
                  order,
                  Some(msg),
                  screen
                )
                logger.error(
                  "pay fail -> {} {}",
                  msg,
                  r.request.order.toJson.jsonTo[Map[String, Any]].mkString("\n")
                )
                Source.single(r)
              case r @ PaySuccess(request) =>
//                orderQueue.offer(IncrmentChrome())
                val order = request.order
                val newRequest = PaySuccess(
                  CreateOrder(
                    order.copy(
                      payCount = order.payCount + 1,
                      payMoney = order.payMoney + order.money
                    )
                  )(request.replyTo)
                )
                sendNotifyMessage(
                  DingDing.MessageType.payed,
                  "充值成功",
                  newRequest.request.order,
                  None,
                  None
                )
                logger.info(
                  "pay success -> {}",
                  newRequest.request.order.toJson
                    .jsonTo[Map[String, Any]]
                    .mkString("\n")
                )
                Source.single(newRequest)
            }
          )

        val queryOrderFlow = Flow[Event]
          .mapAsync(chromeSize) {
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
          .mapAsync(chromeSize) {
            case PaySuccess(request) =>
              orderService.updateAll(
                request.order.copy(
                  pay = true,
                  expire = true
                )
              )
            case PayFail(request, msg, screen) =>
              orderService.updateAll(
                request.order.copy(
                  pay = false,
                  expire = true
                )
              )
            case CreateOrderFail(request, msg, screen) =>
              orderService.updateAll(
                request.order.copy(
                  pay = false,
                  expire = true
                )
              )
          }

        orderSource
        //          .statefulMapConcat { () =>
        //            {
        //              var chromes = chromeSize
        //
        //              {
        //                case r @ CreateOrder(order) => {
        //                  if (chromes > 0) {
        //                    chromes = chromes - 1
        //                    r :: Nil
        //                  } else {
        //                    r.replyTo.tell(
        //                      CreateOrderFail(r, "操作频繁、请稍后再试")
        //                    )
        //                    sendNotifyMessage(
        //                      DingDing.MessageType.order,
        //                      "操作频繁",
        //                      order,
        //                      None
        //                    )
        //                    Nil
        //                  }
        //                }
        //                case IncrmentChrome() => {
        //                  chromes = chromes + 1
        //                  Nil
        //                }
        //              }
        //            }
        //          }
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
                e.replyTo.tell(CreateOrderFail(e, "queue completion", None))
              case QueueOfferResult.Enqueued =>
                logger.info("Enqueued")
              case QueueOfferResult.Dropped =>
                logger.info("Dropped")
                e.replyTo.tell(CreateOrderFail(e, "操作频繁、请稍后重试", None))
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

  def createCoreFlow(
      system: ActorSystem[_]
  ): Flow[Event, Event, NotUsed] = {
    implicit val ec = system.executionContext
    Flow[Event]
      .log()
      .flatMapMerge(
        30,
        {
          case r @ CreateOrder(order) => {
            RestartSource
              .onFailuresWithBackoff(
                RestartSettings(
                  minBackoff = 1.seconds,
                  maxBackoff = 1.seconds,
                  randomFactor = 0.2
                ).withMaxRestarts(1, 1.seconds)
              )(() => {
//                Source(iterable = 0 until ChromePools(system).poolSize())
//                  .flatMapMerge(
//                    10,
//                    id =>
                createQrcodeSource(
                  system,
                  order,
                  0
//                      )
                ).filter(_.isRight)
                  .take(1)
                  .orElse(
                    Source.single(Left((new Exception("all fail"), None)))
                  )
              })
              .flatMapConcat {
                case Left((error, screen)) =>
                  Source(
                    CreateOrderFail(
                      r,
                      error.getMessage,
                      screen
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
                        case Left((error, screen)) =>
                          PayFail(r, error.getMessage, screen)
                        case Right(_) => PaySuccess(r)
                      }
                    )
              }

          }
        }
      )
  }

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
    Either[
      (Throwable, Option[String]),
      (Chrome, OrderModel.DbInfo, String, Int)
    ],
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
            .single(source.driver("douyin_cookie"))
            .mapAsync(1) { driver =>
              Future {
                logger.info(s"切换用户 -> ${order.id}")
                driver.tap(_.findElementByClassName("btn").click())
              }.recover {
                case _ => throw new Exception("无法点击切换用户按钮")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info("输入帐号 -> {}", order.id)
                driver.tap(
                  _.findElementByTagName("input").sendKeys(order.id)
                )
              }.recover {
                case _ => throw new Exception(s"无法输入帐号 -> ${order.id}")
              }
            }
            .mapAsync(1)(driver => {
              Future {
                logger.info("确认帐号 -> {}", order.id)
                driver.tap(_.findElementByClassName("confirm-btn").click())
              }.recover {
                case _ => throw new Exception(s"无法点击确认帐号 -> ${order.id}")
              }
            })
            .mapAsync(1) { driver =>
              Future {
                logger.info("点击自定义充值金额按钮")
                driver.tap(
                  _.findElementByClassName("customer-recharge").click()
                )
              }.recover {
                case _ => throw new Exception(s"无法点击自定义充值按钮")
              }
            }
            .mapAsync(1) { driver =>
              Future {
                logger.info(s"输入充值金额 -> ${order.money}")
                driver.tap(
                  _.findElementByClassName("customer-recharge")
                    .findElement(By.tagName("input"))
                    .sendKeys(order.money.toString)
                )
              }.recover {
                case _ => throw new Exception(s"无法输入充值金额 -> ${order.money}")
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
              Source(1 to 6)
                .throttle(1, 500.milliseconds)
                .filter(_ => {
                  logger.info("判断是否已经跳转")
                  try {
                    driver
                      .findElementByClassName("check-content")
                      .findElement(By.className("right"))
                      .click()
                  } catch {
                    case e =>
                  }
                  driver.getCurrentUrl.contains("tp-pay.snssdk.com")
                })
                .map(_ => Right("已跳转"))
                .take(1)
                .orElse(Source.single(Left(new Exception("没有二次确认框也没跳转"))))
                //                .flatMapConcat {
                //                  case Left(error) => throw error
                //                  case Right(_) =>
                //                    Source(1 to 4)
                //                      .throttle(1, 500.milliseconds)
                //                      .filter(_ => {
                //                        logger.info("判断是否已经跳转")
                //                        driver.getCurrentUrl
                //                          .contains("tp-pay.snssdk.com")
                //                      })
                //                      .map(_ => Right(true))
                //                      .take(1)
                //                      .orElse(
                //                        Source.single(
                //                          Left(new Exception(s"支付支付页面无法跳转 -> ${order.id}"))
                //                        )
                //                      )
                //                }
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
                      case _ => throw new Exception(s"切换微信支付失败 -> ${order.id}")
                    }
                }
                .flatMapConcat { driver =>
                  Source(1 to 3)
                    .throttle(1, 500.milliseconds)
                    .filter(_ => {
                      logger.info("获取二维码支付图片")
                      val findQrcode =
                        try {
                          driver
                            .findElementByClassName(
                              "pay-method-scanpay-qrcode-image"
                            )
                          true
                        } catch {
                          case e => false
                        }
                      findQrcode
                    })
                    .map(_ => Right("已找到"))
                    .take(1)
                    .orElse(
                      Source
                        .single(Left(new Exception(s"支付二维码找不到 -> ${order.id}")))
                    )
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
                val left = Left(
                  (
                    e,
                    Some(
                      source
                        .driver()
                        .getScreenshotAs(OutputType.FILE)
                        .getAbsolutePath
                    )
                  )
                )
                ChromePools(system).pool(id).returnObject(source)
                left
              }
            }
        }
      }
      .recover {
        case e: Throwable => Left((e, None))
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
  ): Source[Either[(Throwable, Option[String]), OrderModel.DbInfo], NotUsed] = {
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
      .orElse(
        Source.single(
          Left(
            (
              new Exception("未支付"),
              Some(
                chrome.driver().getScreenshotAs(OutputType.FILE).getAbsolutePath
              )
            )
          )
        )
      )
      .recover {
        case e => {
          e.printStackTrace()
          Left(
            (
              new Exception("未支付"),
              Some(
                chrome.driver().getScreenshotAs(OutputType.FILE).getAbsolutePath
              )
            )
          )
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
