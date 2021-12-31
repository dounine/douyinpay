package com.dounine.douyinpay.behaviors.engine

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.cluster.sharding.typed.scaladsl.EntityTypeKey
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{
  BaseSerializer,
  BreakDownModel,
  DouyinAccountModel,
  OrderModel
}
import com.dounine.douyinpay.service.{
  BreakDownStream,
  DictionaryService,
  OrderStream
}
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.{
  DingDing,
  QrcodeUrlRandom,
  Request,
  ServiceSingleton
}
import org.slf4j.LoggerFactory

import java.time.{LocalDate, LocalDateTime}
import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object DouyinAccountBehavior extends JsonParse {

  sealed trait Event extends BaseSerializer

  val typeKey: EntityTypeKey[Event] =
    EntityTypeKey[Event]("DouyinAccount")
  private val logger = LoggerFactory.getLogger(DouyinAccountBehavior.getClass)

  case class Query(id: String)(val replyTo: ActorRef[BaseSerializer])
      extends Event

  case class QueryOk(cookie: Option[String]) extends Event

  case class QueryFail(query: Query, msg: String) extends Event

  case class Init() extends Event

  case class Check() extends Event

  case class CheckFail(request: Check, msg: String) extends Event

  case class CheckOk(request: Check) extends Event

  def apply(): Behavior[Event] =
    Behaviors.setup { context =>
      {
        implicit val materializer =
          SystemMaterializer(context.system).materializer
        implicit val ec = context.executionContext
        implicit val sys = context.system
        var accounts = Map[String, Set[String]]()
        val accountSize = 3
        var date = LocalDate.now()
        val accountName = "douyin_account"
//        val proxys = context.system.settings.config
//          .getString("app.qrcodeProxys")
//          .split(",")
        Behaviors.withTimers { timers: TimerScheduler[Event] =>
          {
            Behaviors.receiveMessage {
              case e @ Init() => {
                val accountCookieInfo = Await.result(
                  ServiceSingleton
                    .get(classOf[DictionaryService])
                    .info(accountName),
                  Duration.Inf
                )
                accounts = accountCookieInfo match {
                  case Some(value) =>
                    value.text.jsonTo[Map[String, Set[String]]]
                  case None => Map[String, Set[String]]()
                }
                timers.startTimerAtFixedRate(
                  Check(),
                  60.seconds
                )
                Behaviors.same
              }
              case e @ Check() => {
                context.pipeToSelf(
                  Request
                    .get[DouyinAccountModel.AccountResponse](
                      "http://192.168.0.96:30000/login/account"
                    )
                ) {
                  case Failure(exception) => CheckFail(e, exception.getMessage)
                  case Success(value) =>
                    if (date != LocalDate.now()) {
                      accounts = value.files.toSet
                        .map((_: String) -> Set.empty[String])
                        .toMap
                    } else {
                      accounts = value.files.toSet
                        .map((k: String) => {
                          k -> (accounts.get(k) match {
                            case Some(value) => value
                            case None        => Set.empty[String]
                          })
                        })
                        .toMap
                    }
                    Await.result(
                      ServiceSingleton
                        .get(classOf[DictionaryService])
                        .upsert(accountName, accounts.toJson),
                      Duration.Inf
                    )
                    CheckOk(e)
                }
                Behaviors.same
              }
              case e @ CheckFail(request, msg) => {
                Behaviors.same
              }
              case e @ CheckOk(request) => {
                Behaviors.same
              }
              case e @ Query(id) => {
                accounts.find(_._1.split("\\.").head == id) match {
                  case Some(value) =>
                    e.replyTo.tell(
                      QueryOk(
                        Some(value._1)
                      )
                    )
                  case None =>
                    accounts.find(_._2.contains(id)) match {
                      case Some(value) =>
                        //                    val proxy =
                        //                      s"http://${proxys(accounts.map(_._1).toList.sorted.zipWithIndex.find(_._1 == value._1).get._2 % proxys.size)}:8080"
                        logger.info("id -> {}", value._1)
                        e.replyTo.tell(
                          QueryOk(
                            Some(value._1)
                          )
                        )
                      case None =>
                        accounts.find(_._2.size < accountSize) match {
                          case Some(value) =>
                            accounts = accounts.map(k => {
                              if (k._1 == value._1) {
                                k._1 -> (value._2 + id)
                              } else {
                                k
                              }
                            })
                            //                        val proxy =
                            //                          s"http://${proxys(accounts.map(_._1).toList.sorted.zipWithIndex.find(_._1 == value._1).get._2 % proxys.size)}:8080"
                            logger.info("id -> {}", value._1)
                            e.replyTo.tell(
                              QueryOk(
                                Some(value._1)
                              )
                            )
                          case None =>
                            e.replyTo.tell(
                              QueryOk(None)
                            )
                        }
                    }
                }
                Behaviors.same
              }
            }
          }
        }
      }
    }
}
