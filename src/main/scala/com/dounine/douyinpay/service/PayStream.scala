package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.Flow
import com.dounine.douyinpay.model.models.PayModel
import com.dounine.douyinpay.store.{PayTable, UserTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import slick.dbio.Effect
import slick.jdbc.JdbcBackend
import slick.sql.FixedSqlAction

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

object PayStream {

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
    val cardTable = PayTable()
    Flow[String]
      .mapAsync(1) { orderId =>
        db.run(
          cardTable.filter(_.id === orderId).map(_.pay).update(true).map(_ == 1)
        )
      }
  }

  def payQuery()(implicit
      system: ActorSystem[_]
  ): Flow[String, PayModel.PayInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val cardTable = PayTable()
    Flow[String]
      .mapAsync(1) { orderId =>
        db.run(
          cardTable.filter(_.id === orderId).result.head
        )
      }
  }

  def cardPayed()(implicit
      system: ActorSystem[_]
  ): Flow[PayModel.UpdateCard, (PayModel.UpdateCard, Int), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val cardTable = PayTable()

    Slick.flowWithPassThrough { card =>
      (for {
        result <-
          cardTable
            .filter(_.id === card.id)
            .map(_.pay)
            .update(card.pay)
      } yield (card, result)).transactionally
    }
  }

}
