package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  HttpMethods,
  HttpRequest,
  HttpResponse,
  StatusCode
}
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.SlickSession
import akka.stream.scaladsl.{FileIO, Flow, Source}
import com.dounine.douyinpay.model.models.{AccountModel, OrderModel}
import com.dounine.douyinpay.store.{AccountTable, OrderTable}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.util.{DingDing, QrcodeUrlRandom, Request}
import slick.jdbc.JdbcBackend

import java.nio.file.Files
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.{ExecutionContextExecutor, Future}

object OrderStream {

  def queryOrdersSuccess()(implicit
      system: ActorSystem[_]
  ): Source[
    Map[String, Int],
    NotUsed
  ] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._

    Source.future(
      db.run(
        OrderTable()
          .filter(_.pay === true)
          .groupBy(_.openid)
          .map {
            case (openid, paySuccessOrders) => (openid, paySuccessOrders.length)
          }
          .result
          .map(_.map(i => i._1 -> i._2).toMap)
      )
    )
  }
  def qrcodeCreate()(implicit
      system: ActorSystem[_]
  ): Flow[
    OrderModel.DbInfo,
    (OrderModel.DbInfo, OrderModel.QrcodeResponse),
    NotUsed
  ] = {
    val config = system.settings.config
    val qrcodeUrl = QrcodeUrlRandom.random()
    val domain = config.getString("app.file.domain")
    val routerPrefix = config.getString("app.routerPrefix")
    Flow[OrderModel.DbInfo]
      .mapAsync(1) { order: OrderModel.DbInfo =>
        Request
          .post[OrderModel.QrcodeResponse](
            qrcodeUrl + "/" + order.platform,
            Map(
              "order" -> order,
              "timeout" -> 10 * 1000,
              "callback" -> s"https://backup.61week.com/${routerPrefix}/order/update"
            )
          )
          .map(order -> _)(system.executionContext)
      }
  }

  def downloadQrocdeFile()(implicit
      system: ActorSystem[_]
  ): Flow[(OrderModel.DbInfo, String), (OrderModel.DbInfo, String), NotUsed] = {
    implicit val ec = system.executionContext
    Flow[(OrderModel.DbInfo, String)]
      .mapAsync(1) { tp2 =>
        Http(system)
          .singleRequest(
            request = HttpRequest(
              method = HttpMethods.GET,
              uri = tp2._2
            ),
            settings = ConnectSettings.httpSettings(system)
          )
          .flatMap {
            case HttpResponse(code: StatusCode, value, entity, protocol) =>
              val tmpFile = Files.createTempFile("qrcode", "png")
              entity.dataBytes
                .runWith(FileIO.toPath(tmpFile))
                .map(_ => (tp2._1, tmpFile.toAbsolutePath.toString))
            case msg => {
              Future.failed(new Exception(msg.toString()))
            }
          }
      }
  }

  def notifyOrderCreateStatus()(implicit
      system: ActorSystem[_]
  ): Flow[
    (OrderModel.DbInfo, OrderModel.QrcodeResponse),
    (OrderModel.DbInfo, OrderModel.QrcodeResponse),
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    val wechat = system.settings.config.getConfig("app.wechat")
    val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")
    val notify =
      (
          order: OrderModel.DbInfo,
          title: String,
          mType: DingDing.MessageType.MessageType
      ) => {
        DingDing
          .sendMessageFuture(
            mType,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = "定单通知",
                text = s"""
                        |## ${title}
                        | - 公众号: ${wechat.getString(s"${order.appid}.name")}
                        | - 公众号ID: ${order.appid}
                        | - 当前渠道: ${order.ccode}
                        | - 微信昵称: ${order.nickName.getOrElse("")}
                        | - 充值帐号: ${order.id}
                        | - 本次金额: ${order.money}
                        | - 今日充值次数: ${order.todayPayCount}
                        | - 今日充值金额: ${order.todayPayMoney}
                        | - 全部充值次数: ${order.payCount}
                        | - 全部充值金额: ${order.payMoney}
                        | - 耗时: ${java.time.Duration
                  .between(order.createTime, LocalDateTime.now())
                  .getSeconds}s
                        | - openid: ${order.openid}
                        | - 创建时间: ${order.createTime.format(
                  timeFormatter
                )}
                        | - 通知时间: ${LocalDateTime
                  .now()
                  .format(timeFormatter)}
                        |""".stripMargin
              )
            )
          )
          .map(_ => order)
      }
    Flow[(OrderModel.DbInfo, OrderModel.QrcodeResponse)]
      .mapAsync(1) { tp2 =>
        if (tp2._2.qrcode.isDefined) {
          notify(tp2._1, "创建成功", DingDing.MessageType.order)
            .map(_ => tp2)
            .recover {
              case e => tp2
            }
        } else {
          notify(tp2._1, "创建失败", DingDing.MessageType.orderFail)
            .map(_ => tp2)
            .recover {
              case e => tp2
            }
        }
      }
  }

  def notifyOrderPayStatus()(implicit
      system: ActorSystem[_]
  ): Flow[OrderModel.DbInfo, OrderModel.DbInfo, NotUsed] = {
    implicit val ec = system.executionContext
    val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")
    val wechat = system.settings.config.getConfig("app.wechat")
    val notify = (
        typ: DingDing.MessageType.MessageType,
        order: OrderModel.DbInfo,
        title: String
    ) => {
      DingDing
        .sendMessageFuture(
          typ,
          data = DingDing.MessageData(
            markdown = DingDing.Markdown(
              title = "定单通知",
              text = s"""
                        |## ${title}
                        | - 公众号: ${wechat.getString(s"${order.appid}.name")}
                        | - 公众号ID: ${order.appid}
                        | - 当前渠道: ${order.ccode}
                        | - 微信昵称: ${order.nickName.getOrElse("")}
                        | - 充值帐号: ${order.id}
                        | - 本次金额: ${order.money}
                        | - 今日充值次数: ${order.todayPayCount}
                        | - 今日充值金额: ${order.todayPayMoney}
                        | - 全部充值次数: ${order.payCount}
                        | - 全部充值金额: ${order.payMoney}
                        | - 耗时: ${java.time.Duration
                .between(order.createTime, LocalDateTime.now())
                .getSeconds}s
                        | - openid: ${order.openid}
                        | - 创建时间: ${order.createTime.format(
                timeFormatter
              )}
                        | - 通知时间: ${LocalDateTime
                .now()
                .format(timeFormatter)}
                        |""".stripMargin
            )
          )
        )
        .map(_ => order)
    }
    Flow[OrderModel.DbInfo]
      .via(aggregation())
      .mapAsync(1) { order =>
        if (order.pay) {
          notify(DingDing.MessageType.payed, order, "充值成功")
            .recover {
              case e => order
            }
        } else {
          notify(DingDing.MessageType.payerr, order, "充值失败")
            .recover {
              case e => order
            }
        }
      }
  }

  def queryOrder()(implicit
      system: ActorSystem[_]
  ): Flow[String, OrderModel.DbInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    Flow[String]
      .mapAsync(1) { orderId =>
        db.run(OrderTable().filter(_.orderId === orderId).result.head)
      }
  }

  def updateOrderStatus()(implicit
      system: ActorSystem[_]
  ): Flow[OrderModel.UpdateStatus, (OrderModel.UpdateStatus, Int), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    Flow[OrderModel.UpdateStatus]
      .mapAsync(1) { info =>
        db.run(
            OrderTable()
              .filter(_.orderId === info.order.orderId)
              .result
              .headOption
          )
          .flatMap {
            case Some(value) =>
              db.run(
                  OrderTable()
                    .filter(_.orderId === value.orderId)
                    .map(i => (i.pay, i.expire))
                    .update((info.pay, true))
                )
                .map(info -> _)
            case None => Future.successful((info, 0))
          }
      }
  }

  def add()(implicit
      system: ActorSystem[_]
  ): Flow[OrderModel.DbInfo, (OrderModel.DbInfo, Int), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer
    Flow[OrderModel.DbInfo]
      .mapAsync(1) { order: OrderModel.DbInfo =>
        db.run(OrderTable() += order).map(order -> _)
      }
  }

  def aggregation()(implicit
      system: ActorSystem[_]
  ): Flow[OrderModel.DbInfo, OrderModel.DbInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer
    Flow[OrderModel.DbInfo]
      .mapAsync(1) { order: OrderModel.DbInfo =>
        db.run(
            OrderTable()
              .filter(i => i.openid === order.openid && i.pay === true)
              .result
          )
          .map { list =>
            order.copy(
              payCount = list.size,
              payMoney = list.map(_.money).sum,
              todayPayCount = list
                .filterNot(
                  _.createTime.isBefore(LocalDate.now().atStartOfDay())
                )
                .size,
              todayPayMoney = list
                .filterNot(
                  _.createTime.isBefore(LocalDate.now().atStartOfDay())
                )
                .map(_.money)
                .sum
            )
          }
      }
  }

  def queryPaySum()(implicit
      system: ActorSystem[_]
  ): Flow[String, Option[Int], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          OrderTable()
            .filter(i =>
              i.openid === openid && i.pay === true && i.createTime >= LocalDate
                .now()
                .atStartOfDay()
            )
            .map(_.money)
            .sum
            .result
        )
      }
  }

  def queryOpenidPaySum()(implicit
                    system: ActorSystem[_]
  ): Flow[String, Option[Int], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          OrderTable()
            .filter(i =>
              i.openid === openid && i.pay === true
            )
            .map(_.money)
            .sum
            .result
        )
      }
  }

  def queryTodayPaySum()(implicit
      system: ActorSystem[_]
  ): Source[(Int, Int), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Source
      .future(
        db.run(
          OrderTable()
            .filter(i =>
              i.pay === true && i.createTime >= LocalDate
                .now()
                .minusDays(1)
                .atStartOfDay()
                && i.createTime < LocalDate.now().atStartOfDay()
            )
            .map(_.money)
            .sum
            .result
            .map(_.getOrElse(0))
        )
      )
      .zip(
        Source.future(
          db.run(
            OrderTable()
              .filter(i =>
                i.pay === true && i.createTime >= LocalDate
                  .now()
                  .atStartOfDay()
              )
              .map(_.money)
              .sum
              .result
              .map(_.getOrElse(0))
          )
        )
      )
  }

  def queryOpenidTodayPay()(implicit
      system: ActorSystem[_]
  ): Flow[String, Seq[OrderModel.DbInfo], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          OrderTable()
            .filter(i =>
              i.pay === true && i.createTime >= LocalDate
                .now()
                .atStartOfDay() && i.openid === openid && i.fee === 0
            )
            .result
        )
      }
  }

}
