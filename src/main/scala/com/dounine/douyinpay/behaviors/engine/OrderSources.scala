package com.dounine.douyinpay.behaviors.engine

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.event.LogMarker
import akka.stream.Attributes
import akka.stream.scaladsl.Flow
import com.dounine.douyinpay.behaviors.engine.ChromeSources.Event
import com.dounine.douyinpay.model.models.{BaseSerializer, OrderModel}
import com.dounine.douyinpay.model.types.service.PayStatus
import com.dounine.douyinpay.service.{OrderService, UserService}
import com.dounine.douyinpay.tools.json.{ActorSerializerSuport, JsonParse}
import com.dounine.douyinpay.tools.util.{DingDing, ServiceSingleton}
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.{Future, Promise}

object OrderSources extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(OrderSources.getClass)

  sealed trait Event extends BaseSerializer

  case class Create(order: OrderModel.FutureCreateInfo) extends Event

  case class Cancel(orderId: Long) extends Event

  case class PayError(request: QrcodeSources.CreateOrderPush, error: String)
      extends Event

  private case class QueryOrder(repeat: Int, pub: Boolean) extends Event

  private case class QueryOrderOk(
      request: QueryOrder,
      orders: Seq[OrderModel.DbInfo]
  ) extends Event

  private case class QueryOrderFail(request: QueryOrder, error: String)
      extends Event

  private case class UpdateOrder(
      order: OrderModel.DbInfo,
      paySuccess: Boolean
  ) extends Event

  private case class UpdateOrderOk(request: UpdateOrder) extends Event

  private case class UpdateOrderFail(
      request: UpdateOrder,
      error: String
  ) extends Event

  case class PaySuccess(request: QrcodeSources.CreateOrderPush) extends Event

  case class AppWorkPush(
      id: String
  ) extends Event

  case class PayPush(
      request: QrcodeSources.CreateOrderPush,
      qrcode: String
  ) extends Event

  implicit class FlowLog(data: Flow[BaseSerializer, BaseSerializer, NotUsed])
      extends JsonParse {
    def log(): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
      data
        .logWithMarker(
          s"orderMarker",
          (e: BaseSerializer) =>
            LogMarker(
              name = s"orderMarker"
            ),
          (e: BaseSerializer) => e.logJson
        )
        .withAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Info
          )
        )
    }
  }

  def createCoreFlow(
      system: ActorSystem[_]
  ): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
    implicit val ec = system.executionContext
    val orderService = ServiceSingleton.get(classOf[OrderService])
    Flow[BaseSerializer]
      .collectType[Event]
      .log()
      .statefulMapConcat { () =>
        var orders = Set[OrderModel.DbInfo]()
        var ordersPromise = Map[String, Promise[String]]()

        {
          case PayPush(request, qrcode) => {
            ordersPromise(request.order.orderId).success(qrcode)
            Nil
          }
          case UpdateOrderOk(request) => {
            Nil
          }
          case UpdateOrderFail(request, error) => {
            Nil
          }
          case request @ AppWorkPush(id) => {
            if (orders.nonEmpty) {
              val earlierOrder = orders.minBy(_.createTime)
              orders = orders.filterNot(_.orderId == earlierOrder.orderId)
              QrcodeSources.CreateOrderPush(request, earlierOrder) :: Nil
            } else {
              ChromeSources.Idle(
                id = id
              ) :: Nil
            }
          }
          case Create(info) => {
            val order = info.info
            orders = orders ++ Set(order)
            ordersPromise = ordersPromise ++ Map(
              order.orderId -> info.success
            )
            DingDing.sendMessage(
              DingDing.MessageType.order,
              data = DingDing.MessageData(
                markdown = DingDing.Markdown(
                  title = "定单通知",
                  text = s"""
                            |## 创建成功
                            | - nickName: ${order.nickName}
                            | - id: ${order.id}
                            | - money: ${order.money}
                            | - payCount: 0
                            | - createTime: ${order.createTime}
                            | - orderId: ${order.orderId}
                            | - notifyTime -> ${LocalDateTime.now()}
                            |""".stripMargin
                )
              ),
              system
            )
            Nil
          }
          case PaySuccess(request) => {
            val order = request.order.copy(
              pay = true
            )
            DingDing.sendMessage(
              DingDing.MessageType.payed,
              data = DingDing.MessageData(
                markdown = DingDing.Markdown(
                  title = "充值通知",
                  text = s"""
                            |## 充值成功
                            | - nickName: ${order.nickName}
                            | - id: ${order.id}
                            | - money: ${order.money}
                            | - payCount: 0
                            | - createTime: ${order.createTime}
                            | - orderId: ${order.orderId}
                            | - notifyTime -> ${LocalDateTime.now()}
                            |""".stripMargin
                )
              ),
              system
            )
            UpdateOrder(
              request.order.copy(
                pay = true
              ),
              paySuccess = true
            ) :: Nil
          }
          case PayError(request, error) => {
            val order = request.order
            orders = orders.filterNot(_.orderId == request.order.orderId)
            ordersPromise =
              ordersPromise.filterNot(_._1 == request.order.orderId)
            DingDing.sendMessage(
              DingDing.MessageType.payerr,
              data = DingDing.MessageData(
                markdown = DingDing.Markdown(
                  title = "充值通知",
                  text = s"""
                              |## 充值失败
                              | - nickName: ${order.nickName}
                              | - id: ${order.id}
                              | - money: ${order.money}
                              | - payCount: 0
                              | - createTime: ${order.createTime}
                              | - orderId: ${order.orderId}
                              | - notifyTime -> ${LocalDateTime.now()}
                              |""".stripMargin
                )
              ),
              system
            )
            UpdateOrder(
              order = order,
              paySuccess = false
            ) :: Nil
          }
          case ee @ _ => ee :: Nil
        }
      }
      .log()
      .mapAsync(1) {
        case request @ UpdateOrder(order, paySuccess) => {
          orderService
            .updateAll(order)
            .map {
              case 1 => UpdateOrderOk(request)
              case _ => UpdateOrderFail(request, "update fail")
            }
            .recover {
              case ee => UpdateOrderFail(request, ee.getMessage)
            }
        }
        case ee @ _ => {
          Future.successful(ee)
        }
      }
      .log()

  }

}
