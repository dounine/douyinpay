package com.dounine.douyinpay.behaviors.engine

import akka.actor.typed.{ActorRef, Behavior}
import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.stream.SystemMaterializer
import com.dounine.douyinpay.model.models.BaseSerializer
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.DingDing
import org.slf4j.LoggerFactory

import java.time.LocalDateTime
import scala.concurrent.duration._

object CardBehavior extends JsonParse {

  private val logger = LoggerFactory.getLogger(CardBehavior.getClass)
  sealed trait Event extends BaseSerializer

  type MoneyEnum = Int
  type Money = BigDecimal
  type Openid = String
  case class BindCardRequest(openid: Openid, money: MoneyEnum)(
      val replyTo: ActorRef[Event]
  ) extends Event
  case class BindCardOk(request: BindCardRequest, money: Money) extends Event
  case class BindCardFail(request: BindCardRequest, msg: String) extends Event
  case class BindCardTimeout(request: BindCardRequest, money: Money)
      extends Event
  case class PhonePaySuccess(money: Money) extends Event
  def apply(): Behavior[Event] =
    Behaviors.setup { context =>
      {
        implicit val materializer =
          SystemMaterializer(context.system).materializer
        implicit val ec = context.executionContext

        var cards = Map[MoneyEnum, Map[Money, Option[Openid]]]()
        Behaviors.withTimers { timers: TimerScheduler[Event] =>
          {
            Behaviors.receiveMessage {
              case e @ PhonePaySuccess(money) => {
                logger.info("充值成功 -> {}", e.toJson)
                val exits = cards.find(_._2.contains(money))
                exits match {
                  case Some(value) =>
                    value._2(money) match {
                      case Some(openid) => {
                        DingDing.sendMessage(
                          DingDing.MessageType.phonePaySuccess,
                          data = DingDing.MessageData(
                            markdown = DingDing.Markdown(
                              title = "支付成功",
                              text = s"""
                                        |# 支付成功
                                        | - openid: ${openid}
                                        | - money: ${money}
                                        | - time: ${LocalDateTime.now()}
                                        |""".stripMargin
                            )
                          ),
                          context.system
                        )
                      }
                      case None =>
                        DingDing.sendMessage(
                          DingDing.MessageType.phonePayFail,
                          data = DingDing.MessageData(
                            markdown = DingDing.Markdown(
                              title = "支付的金额用户没绑定",
                              text = s"""
                                        |# 支付的金额用户没绑定
                                        | - money: ${money}
                                        | - time: ${LocalDateTime.now()}
                                        |""".stripMargin
                            )
                          ),
                          context.system
                        )
                    }
                  case None =>
                    DingDing.sendMessage(
                      DingDing.MessageType.phonePayFail,
                      data = DingDing.MessageData(
                        markdown = DingDing.Markdown(
                          title = "支付了不存在的金额",
                          text = s"""
                                    |# 支付了不存在的金额
                                    | - money: ${money}
                                    | - time: ${LocalDateTime.now()}
                                    |""".stripMargin
                        )
                      ),
                      context.system
                    )
                }
                Behaviors.same
              }
              case e @ BindCardTimeout(request, money) => {
                logger.info("充值超时 -> {}", e.toJson)
                DingDing.sendMessage(
                  DingDing.MessageType.phonePayFail,
                  data = DingDing.MessageData(
                    markdown = DingDing.Markdown(
                      title = "超时未支付",
                      text = s"""
                                |# 超时未支付
                                | - openid: ${request.openid}
                                | - money: ${money}
                                | - time: ${LocalDateTime.now()}
                                |""".stripMargin
                    )
                  ),
                  context.system
                )
                cards = cards + (request.money -> (cards(
                  request.money
                ) + (money -> None)))
                Behaviors.same
              }
              case e @ BindCardRequest(openid, money: MoneyEnum) => {
                val exitBind =
                  cards.find(_._2.values.exists(p => p.contains(openid)))
                exitBind match {
                  case Some(binded) =>
                    timers.startSingleTimer(
                      openid + money,
                      BindCardTimeout(e, money),
                      60.seconds
                    )
                    binded._2.find(_._2.contains(openid)) match {
                      case Some(exitBind) =>
                        e.replyTo.tell(
                          BindCardOk(
                            e,
                            exitBind._1
                          )
                        )
                    }
                  case None =>
                    val notUsedCards = cards(money).filter(_._2.isEmpty)
                    if (notUsedCards.nonEmpty) {
                      val max = notUsedCards.toSeq.maxBy(_._1)
                      timers.startSingleTimer(
                        openid + money,
                        BindCardTimeout(e, max._1),
                        60.seconds
                      )
                      DingDing.sendMessage(
                        DingDing.MessageType.phonePayBind,
                        data = DingDing.MessageData(
                          markdown = DingDing.Markdown(
                            title = "绑定金额成功",
                            text = s"""
                                      |# 绑定金额成功
                                      | - openid: ${openid}
                                      | - money: ${money}
                                      | - time: ${LocalDateTime.now()}
                                      |""".stripMargin
                          )
                        ),
                        context.system
                      )
                      e.replyTo.tell(
                        BindCardOk(
                          e,
                          max._1
                        )
                      )
                    } else {
                      e.replyTo.tell(BindCardFail(e, "当前充值人数过多、请稍微再试"))
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
