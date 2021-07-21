package com.dounine.douyinpay.behaviors.engine

import akka.NotUsed
import akka.actor.typed.{ActorRef, ActorSystem}
import akka.event.LogMarker
import akka.stream.scaladsl.{DelayStrategy, Flow}
import akka.stream.{Attributes, DelayOverflowStrategy}
import com.dounine.douyinpay.model.models.BaseSerializer
import com.dounine.douyinpay.tools.json.{ActorSerializerSuport, JsonParse}
import org.slf4j.LoggerFactory

import scala.concurrent.duration._

object ChromeSources extends ActorSerializerSuport {

  private val logger = LoggerFactory.getLogger(ChromeSources.getClass)

  sealed trait Event extends BaseSerializer

  case class Online(
      id: String
  ) extends Event

  case class Offline(id: String) extends Event

  case class Idle(
      id: String
  ) extends Event

  case class Finish(
      id: String
  ) extends Event

  implicit class FlowLog(data: Flow[BaseSerializer, BaseSerializer, NotUsed])
      extends JsonParse {
    def log(): Flow[BaseSerializer, BaseSerializer, NotUsed] = {
      data
        .logWithMarker(
          s"appMarker",
          (e: BaseSerializer) =>
            LogMarker(
              name = s"appMarker"
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
    val domain = system.settings.config.getString("app.file.domain")

    Flow[BaseSerializer]
      .collectType[Event]
      .delayWith(
        delayStrategySupplier = () =>
          DelayStrategy.linearIncreasingDelay(
            increaseStep = 1.seconds,
            needsIncrease = {
              case Idle(_) => true
              case _       => false
            },
            maxDelay = 3.seconds
          ),
        DelayOverflowStrategy.backpressure
      )
      .log()
      .statefulMapConcat { () =>
        var online = Set[String]()
        var offline = Set[String]()

        {
          case Online(id) => {
            online = online ++ Set(id)
            OrderSources.AppWorkPush(id) :: Nil
          }
          case Offline(id) => {
            offline = offline ++ Set(id)
            online = online.filterNot(_ == id)
            Nil
          }
          case Idle(id) => {
            if (offline.contains(id)) {
              offline = offline.filterNot(_ == id)
              Nil
            } else {
              OrderSources.AppWorkPush(
                id
              ) :: Nil
            }
          }
          case Finish(id) => {
            if (offline.contains(id)) {
              offline = offline.filterNot(_ == id)
              Nil
            } else {
              OrderSources.AppWorkPush(
                id
              ) :: Nil
            }
          }
          case ee @ _ => ee :: Nil
        }
      }
      .log()
  }

}
