package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.{Flow, Source}
import com.dounine.douyinpay.model.models.PayModel
import com.dounine.douyinpay.model.types.service.PayStatus
import com.dounine.douyinpay.model.types.service.PayStatus.PayStatus
import com.dounine.douyinpay.store.{EnumMappers, PayTable, UserTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.json.JsonParse
import slick.ast.BaseTypedType
import slick.dbio.Effect
import slick.jdbc.H2Profile.MappedColumnType
import slick.jdbc.{JdbcBackend, JdbcType}
import slick.sql.FixedSqlAction
import slick.jdbc.MySQLProfile.api._
import java.time.{LocalDate, LocalDateTime}
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

object PayStream {

  implicit val payStatusMapper
      : JdbcType[PayStatus] with BaseTypedType[PayStatus] =
    MappedColumnType.base[PayStatus, String](
      e => e.toString,
      s => PayStatus.withName(s)
    )

  def queryTodayPaySum()(implicit
      system: ActorSystem[_]
  ): Source[(PayModel.PayReport, PayModel.PayReport), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Source
      .future(
        db.run(
          PayTable()
            .filter(i =>
              i.createTime >= LocalDate
                .now()
                .minusDays(1)
                .atStartOfDay()
                && i.createTime < LocalDate.now().atStartOfDay()
            )
            .result
            .map(result => {
              val payeds = result
                .filter(_.pay == PayStatus.payed)
              val refunds = result
                .filter(_.pay == PayStatus.refund)
              PayModel.PayReport(
                payedCount = payeds.size,
                payedMoney = payeds.map(_.money).sum / 100,
                payedPeople = payeds.map(_.openid).distinct.size,
                refundCount = refunds.size,
                refundMoney = refunds.map(_.money).sum / 100,
                refundPeople = refunds.map(_.openid).distinct.size
              )
            })
        )
      )
      .zip(
        Source.future(
          db.run(
            PayTable()
              .filter(i =>
                i.createTime >= LocalDate
                  .now()
                  .atStartOfDay()
              )
              .result
              .map(result => {
                val payeds = result
                  .filter(_.pay == PayStatus.payed)
                val refunds = result
                  .filter(_.pay == PayStatus.refund)
                PayModel.PayReport(
                  payedCount = payeds.size,
                  payedMoney = payeds.map(_.money).sum / 100,
                  payedPeople = payeds.map(_.openid).distinct.size,
                  refundCount = refunds.size,
                  refundMoney = refunds.map(_.money).sum / 100,
                  refundPeople = refunds.map(_.openid).distinct.size
                )
              })
          )
        )
      )
  }

  def createPay()(implicit
      system: ActorSystem[_]
  ): Flow[PayModel.PayInfo, PayModel.PayInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val cardTable = PayTable()

    val insertCard: PayModel.PayInfo => DBIO[Int] =
      (card: PayModel.PayInfo) => cardTable += card

    Flow[PayModel.PayInfo]
      .via(
        Slick
          .flowWithPassThrough { info =>
            (for {
              card: Int <- insertCard(
                info
              )
            } yield info).transactionally
          }
      )
  }

  def paySuccess()(implicit
      system: ActorSystem[_]
  ): Flow[String, Boolean, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val payTable = PayTable()
    Flow[String]
      .mapAsync(1) { orderId: String =>
        db.run(
            payTable
              .filter(_.id === orderId)
              .map(_.pay)
              .result
              .head
          )
          .flatMap {
            case PayStatus.payed => Future.successful(false)
            case e =>
              db.run(
                payTable
                  .filter(_.id === orderId)
                  .map(_.pay)
                  .update(PayStatus.payed)
                  .map(_ == 1)
              )
          }
      }
  }

  def query()(implicit
      system: ActorSystem[_]
  ): Flow[String, PayModel.PayInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val payTable = PayTable()
    Flow[String]
      .mapAsync(1) { orderId =>
        db.run(
          payTable.filter(_.id === orderId).result.head
        )
      }
  }

  def queryOpenidPaySum()(implicit
      system: ActorSystem[_]
  ): Flow[String, Seq[PayModel.PayInfo], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val payTable = PayTable()
    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          payTable
            .filter(i => i.openid === openid)
            .result
        )
      }
  }

  def updateStatus(status: PayStatus)(implicit
      system: ActorSystem[_]
  ): Flow[String, Int, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val payTable = PayTable()
    Flow[String]
      .mapAsync(1) { payId =>
        db.run(
          payTable.filter(_.id === payId).map(_.pay).update(status)
        )
      }
  }

  def todayPay()(implicit
      system: ActorSystem[_]
  ): Flow[(LocalDate, String), Seq[PayModel.PayInfo], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val payTable = PayTable()
    Flow[(LocalDate, String)]
      .mapAsync(1) { tp2 =>
        db.run(
          payTable
            .filter(i =>
              i.openid === tp2._2 && i.createTime >= tp2._1
                .atStartOfDay() && i.createTime < tp2._1
                .plusDays(1)
                .atStartOfDay()
            )
            .result
        )
      }
  }

  def queryTodayNewUserPay()(implicit
      system: ActorSystem[_]
  ): Source[(List[PayModel.NewUserPay], List[PayModel.NewUserPay]), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer
    val today = LocalDate.now().toString
    val yestoday = LocalDate.now().minusDays(1).toString

    Source
      .future(
        db.run(
            sql"""select
                        openid,
                        sum(money) as payMoney,
                        count(1) as payCount
                from douyinpay_pay
                where createTime >= ${yestoday}
                and createTime < ${today}
                and pay = 'payed'
                and openid not in
                (
                    select distinct(openid)
                    from douyinpay_pay
                    where date_format(createTime, '%Y-%m-%d') < ${yestoday} and pay = 'payed'
                )
                group by openid""".stripMargin.as[(String, Int, Int)]
          )
          .map(result => {
            result.toList.map(i => {
              PayModel.NewUserPay(
                i._1,
                i._2,
                i._3
              )
            })
          })
      )
      .zip(
        Source.future(
          db.run(
              sql"""select
                        openid,
                        sum(money) as payMoney,
                        count(1) as payCount
                from douyinpay_pay
                where createTime >= ${today}
                and pay = 'payed'
                and openid not in
                (
                    select distinct(openid)
                    from douyinpay_pay
                    where date_format(createTime, '%Y-%m-%d') < ${today} and pay = 'payed'
                )
                group by openid""".stripMargin.as[(String, Int, Int)]
            )
            .map(result => {
              result.toList.map(i => {
                PayModel.NewUserPay(
                  i._1,
                  i._2,
                  i._3
                )
              })
            })
        )
      )
  }

}
