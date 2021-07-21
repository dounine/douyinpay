package com.dounine.douyinpay.behaviors.engine

import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, Behavior, PostStop}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.persistence.typed.PersistenceId
import akka.stream._
import akka.stream.scaladsl.{
  Broadcast,
  Flow,
  GraphDSL,
  Keep,
  Merge,
  RunnableGraph,
  Sink,
  Source,
  SourceQueueWithComplete
}
import akka.{Done, NotUsed}
import com.dounine.douyinpay.model.models.OrderModel.FutureCreateInfo
import com.dounine.douyinpay.model.models.{BaseSerializer, OrderModel}
import com.dounine.douyinpay.tools.json.{ActorSerializerSuport, JsonParse}
import com.dounine.douyinpay.tools.util.DingDing
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.{Future, Promise}
import scala.util.{Failure, Success}

object CoreEngine extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(CoreEngine.getClass)

  val typeKey: EntityTypeKey[BaseSerializer] =
    EntityTypeKey[BaseSerializer]("CoreEngineBehavior")

  case class Message(message: BaseSerializer)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends BaseSerializer

  case class MessageOk(request: Message) extends BaseSerializer

  case class MessageFail(request: Message, msg: String) extends BaseSerializer

  case class CreateOrder(order: OrderModel.DbInfo)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends BaseSerializer

  case class CreateOrderOk(request: CreateOrder, qrcode: String)
      extends BaseSerializer

  case class CreateOrderFail(request: CreateOrder, error: String)
      extends BaseSerializer

  case class CancelOrder(orderId: Long)(val replyTo: ActorRef[BaseSerializer])
      extends BaseSerializer

  case class CancelOrderOk(request: CancelOrder) extends BaseSerializer

  case class CancelOrderFail(request: CancelOrder, error: String)
      extends BaseSerializer

  case class Init() extends BaseSerializer

  case class Shutdown()(val replyTo: ActorRef[Done]) extends BaseSerializer

  case object ShutdownStream extends BaseSerializer

  def apply(
      entityId: PersistenceId
  ): Behavior[BaseSerializer] =
    Behaviors.setup[BaseSerializer] { context: ActorContext[BaseSerializer] =>
      {
        val appFlow: Flow[BaseSerializer, BaseSerializer, NotUsed] =
          ChromeSources.createCoreFlow(context.system)
        val orderFlow: Flow[BaseSerializer, BaseSerializer, NotUsed] =
          OrderSources.createCoreFlow(context.system)
//        val qrcodeFlow: Flow[BaseSerializer, BaseSerializer, NotUsed] =
//          QrcodeSources.createCoreFlow(context.system)

        implicit val materializer: Materializer = Materializer(context)

        val chromes: Seq[String] = (0 until 1).map(_.toString)

        val (
          (
            queue: BoundedSourceQueue[BaseSerializer],
            shutdown: Promise[Option[BaseSerializer]]
          ),
          source: Source[BaseSerializer, NotUsed]
        ) = Source
          .queue[BaseSerializer](1)
          .concatMat(Source.maybe[BaseSerializer])(Keep.both)
          .preMaterialize()

        RunnableGraph
          .fromGraph(GraphDSL.create(source) {
            implicit builder: GraphDSL.Builder[NotUsed] =>
              (request: SourceShape[BaseSerializer]) =>
                {
                  import GraphDSL.Implicits._
                  val broadcast
                      : UniformFanOutShape[BaseSerializer, BaseSerializer] =
                    builder.add(Broadcast[BaseSerializer](4))
                  val merge =
                    builder.add(Merge[BaseSerializer](5))

                  val init = Source(chromes.map(ChromeSources.Online))

                  /*                                            ┌─────────────┐
                   *                 ┌──────┐            ┌─────▶□ Sink.ignore │
                   *                 │ init │            │      └─────────────┘
                   *                 └──□───┘            │         ┌───────┐
                   *                    │                ├────────▶□  app  □ ─────┐
                   *                    ▼                │         └───────┘      │
                   * ┌─────────┐   ┌────□────┐     ┌─────□─────┐   ┌───────┐      │
                   * │ request □─┐ │  merge  □ ──▶ □ broadcast │──▶□ order □ ─────┤
                   * └─────────┘ │ └──□─□─□─□┘     └─────□─────┘   └───────┘      │
                   *             │    ▲ ▲ ▲ ▲            │         ┌───────┐      │
                   *             │    │ │ │ │            └────────▶□qrcode □ ─────┤
                   *             │    │ │ │ │                      └───────┘      │
                   *             └────┘ └─┴─┴─────────────────────────────────────┘
                   */
                  request ~> merge ~> broadcast ~> appFlow ~> merge.in(1)
                  broadcast ~> orderFlow ~> merge.in(2)
                  broadcast ~> orderFlow ~> merge.in(3)
                  broadcast ~> Sink.ignore
                  merge.in(4) <~ init

                  ClosedShape
                }
          })
          .run()

        Behaviors.receiveMessage[BaseSerializer] {
          case e @ Init() => {
            Behaviors.same
          }
          case e @ CreateOrder(order) => {
            val futureInfo = FutureCreateInfo(order, Promise[String]())
            queue
              .offer(OrderSources.Create(futureInfo)) match {
              case result: QueueCompletionResult =>
                futureInfo.success.future.foreach(qrcode => {
                  logger.info("future completion -> ", qrcode)
                  e.replyTo.tell(CreateOrderFail(e, "completion"))
                })(context.executionContext)
              case QueueOfferResult.Enqueued =>
                futureInfo.success.future.foreach(qrcode => {
                  logger.info("future success -> ", qrcode)
                  e.replyTo.tell(CreateOrderOk(e, qrcode))
                })(context.executionContext)
              case QueueOfferResult.Dropped =>
                futureInfo.success.future.foreach(qrcode => {
                  logger.info("future dropped -> ", qrcode)
                  e.replyTo.tell(CreateOrderFail(e, "操作太频繁、请稍后再试"))
                })(context.executionContext)

            }
//            context.pipeToSelf(
//              queue
//                .offer(OrderSources.Create(futureInfo))
//                .flatMap {
//                  case result: QueueCompletionResult =>
//                    logger.error("系统异常 -> {}", e.logJson)
//                    Future.successful(CreateOrderFail(e, "系统异常、请联系管理员"))
//                  case QueueOfferResult.Enqueued =>
//                    futureInfo.success.future.map(qrcode => {
//                      logger.info("future success -> ", qrcode)
//                      CreateOrderOk(e, qrcode)
//                    })(context.executionContext)
//                  case QueueOfferResult.Dropped =>
//                    logger.error("操作太频繁 -> {}", e.logJson)
//                    Future.successful(CreateOrderFail(e, "操作太频繁、请稍后再试"))
//                }(context.executionContext)
//            ) {
//              case Failure(exception) =>
//                CreateOrderFail(e, exception.getMessage)
//              case Success(value: BaseSerializer) => {
//                logger.info("success -> {}", value)
//                value
//              }
//            }
            Behaviors.same
          }
          case e @ CreateOrderOk(request, qrcode) => {
            request.replyTo.tell(e)
            Behaviors.same
          }
          case e @ CreateOrderFail(request, error) => {
            request.replyTo.tell(e)
            Behaviors.same
          }
          case e @ MessageFail(request, msg) => {
            request.replyTo.tell(e)
            Behaviors.same
          }
          case e @ MessageOk(request) => {
            request.replyTo.tell(e)
            Behaviors.same
          }
          case e @ Message(message) => {
            queue.offer(message) match {
              case result: QueueCompletionResult =>
                e.replyTo.tell(MessageFail(e, "completion"))
              case QueueOfferResult.Enqueued => e.replyTo.tell(MessageOk(e))
              case QueueOfferResult.Dropped =>
                e.replyTo.tell(MessageFail(e, "操作太频繁、请稍后再试"))
            }
//            context.pipeToSelf(
//              queue.offer(message)
//            ) {
//              case Failure(exception) => MessageFail(e, exception.getMessage)
//              case Success(value) => {
//                value match {
//                  case result: QueueCompletionResult => {
//                    logger.error("系统异常 -> {}", message.logJson)
//                    MessageFail(e, "系统异常、请联系管理员")
//                  }
//                  case QueueOfferResult.Enqueued => {
//                    MessageOk(e)
//                  }
//                  case QueueOfferResult.Dropped => {
//                    logger.error("操作太频繁 -> {}", message.logJson)
//                    MessageFail(e, "操作太频繁、请稍后再试")
//                  }
//                }
//              }
//            }
            Behaviors.same
          }
          case request @ Shutdown() => {
            shutdown.trySuccess(Option(ShutdownStream))
            request.replyTo.tell(Done)
            Behaviors.same
          }
        }
      }.receiveSignal {
        case (context, PostStop) => {
          DingDing.sendMessage(
            DingDing.MessageType.system,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = "系统通知",
                text = s"""
                            |# CoreEngine 引擎退出
                            | - time: ${LocalDateTime.now()}
                            |""".stripMargin
              )
            ),
            context.system
          )
          Behaviors.stopped
        }
      }
    }

}
