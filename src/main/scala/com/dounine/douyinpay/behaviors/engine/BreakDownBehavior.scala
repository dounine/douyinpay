package com.dounine.douyinpay.behaviors.engine

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{
  BaseSerializer,
  BreakDownModel,
  OrderModel
}
import com.dounine.douyinpay.service.{BreakDownStream, OrderStream}
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.{DingDing, QrcodeUrlRandom, Request}
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object BreakDownBehavior extends JsonParse {

  private val logger = LoggerFactory.getLogger(BreakDownBehavior.getClass)
  sealed trait Event extends BaseSerializer

  case class Create(info: BreakDownModel.BreakDownInfo)(
      val replyTo: ActorRef[BaseSerializer]
  ) extends Event

  case class CreateOk(request: Create) extends Event

  case class CreateFail(request: Create, msg: String) extends Event

  case class Init(list: Seq[BreakDownModel.BreakDownInfo]) extends Event

  case class Check(info: BreakDownModel.BreakDownInfo) extends Event

  case class CheckFail(request: Check, msg: String) extends Event

  case class CheckOk(request: Check) extends Event

  def apply(): Behavior[Event] =
    Behaviors.setup { context =>
      {
        implicit val materializer =
          SystemMaterializer(context.system).materializer
        implicit val ec = context.executionContext
        implicit val sys = context.system

        var breakdowns = Seq[BreakDownModel.BreakDownInfo]()
        Behaviors.withTimers { timers: TimerScheduler[Event] =>
          {
            Behaviors.receiveMessage {
              case e @ Check(info) => {
                val result = Source
                  .single(info.orderId)
                  .via(
                    OrderStream.queryOrder()
                  )
                  .mapAsync(1) { order: OrderModel.DbInfo =>
                    {
                      val qrcodeUrl = QrcodeUrlRandom.random()
                      Request
                        .post[OrderModel.QrcodeResponse](
                          qrcodeUrl + "/" + order.platform,
                          Map(
                            "order" -> order,
                            "timeout" -> 10 * 1000
                          )
                        )
                    }
                  }
                  .flatMapConcat(result => {
                    result.codeUrl match {
                      case Some(value) =>
                        Source
                          .single(
                            info.id
                          )
                          .via(
                            BreakDownStream.success()
                          )
                          .map(info -> _)
                      case None => Source.single(false).map(info -> _)
                    }
                  })
                  .runWith(Sink.head)

//                context.pipeToSelf(result) {
//                  case Failure(exception) => CheckFail(e, exception.getMessage)
//                  case Success(value)     => if(value._2){
//
//                  }else{
//
//                  }
//                }

                Behaviors.same
              }
              case Init(list) => {
                breakdowns = list
                list.zipWithIndex.foreach {
                  case (info, i) =>
                    timers.startSingleTimer(
                      info.id,
                      Check(info),
                      ((1 + i) * 10).seconds
                    )
                }
                Behaviors.same
              }
              case e @ Create(info) => {
                breakdowns.find(i =>
                  i.openid == info.openid && i.account == info.id
                ) match {
                  case Some(value) =>
                  case None => {
                    timers.startSingleTimer(info.id, Check(info), 1.hours)
                    breakdowns = breakdowns ++ Seq(info)
                  }
                }
                e.replyTo.tell(CreateOk(e))
                Behaviors.same
              }

            }
          }
        }
      }
    }
}
