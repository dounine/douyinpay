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
            qrcodeUrl,
            Map(
              "orderId" -> order.orderId,
              "money" -> order.money,
              "id" -> order.id,
              "timeout" -> 10 * 1000,
              "callback" -> s"${domain}/${routerPrefix}/order/update"
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
    val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")
    val notify = (order: OrderModel.DbInfo, title: String) => {
      DingDing
        .sendMessageFuture(
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
                        | - duration: ${java.time.Duration
                .between(order.createTime, LocalDateTime.now())
                .getSeconds}s
                        | - createTime: ${order.createTime.format(
                timeFormatter
              )}
                        | - notifyTime: ${LocalDateTime
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
          notify(tp2._1, "创建成功")
            .map(_ => tp2)
            .recover {
              case e => tp2
            }
        } else {
          notify(tp2._1, "创建失败")
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
                      | - nickName: ${order.nickName.getOrElse("")}
                      | - id: ${order.id}
                      | - money: ${order.money}
                      | - payCount: ${order.payCount}
                      | - payMoney: ${order.payMoney}
                      | - duration: ${java.time.Duration
                .between(order.createTime, LocalDateTime.now())
                .getSeconds}s
                      | - createTime: ${order.createTime.format(
                timeFormatter
              )}
                      | - notifyTime: ${LocalDateTime
                .now()
                .format(timeFormatter)}
                      |""".stripMargin
            )
          )
        )
        .map(_ => order)
    }
    Flow[OrderModel.DbInfo]
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
    val orderTable = TableQuery[OrderTable]
    Flow[String]
      .mapAsync(1) { orderId =>
        db.run(orderTable.filter(_.orderId === orderId).result.head)
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
    val orderTable = TableQuery[OrderTable]
    Flow[OrderModel.UpdateStatus]
      .mapAsync(1) { order =>
        db.run(orderTable.filter(_.orderId === order.orderId).result.headOption)
          .flatMap {
            case Some(value) =>
              db.run(
                  orderTable
                    .filter(_.orderId === value.orderId)
                    .map(i => (i.pay, i.expire))
                    .update((order.pay, true))
                )
                .map(order -> _)
            case None => Future.successful((order, 0))
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
    val orderTable = TableQuery[OrderTable]
    implicit val materializer = SystemMaterializer(system).materializer
    Flow[OrderModel.DbInfo]
      .mapAsync(1) { order: OrderModel.DbInfo =>
        db.run(orderTable += order).map(order -> _)
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
    val orderTable = TableQuery[OrderTable]
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          orderTable
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

  def queryTodayPaySum()(implicit
      system: ActorSystem[_]
  ): Source[(Int, Int), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val orderTable = TableQuery[OrderTable]
    implicit val materializer = SystemMaterializer(system).materializer

    Source
      .future(
        db.run(
          orderTable
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
            orderTable
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

}
