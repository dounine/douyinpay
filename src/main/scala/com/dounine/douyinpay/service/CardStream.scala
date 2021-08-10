package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.Flow
import com.dounine.douyinpay.model.models.CardModel
import com.dounine.douyinpay.store.{CardTable, UserTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import slick.dbio.Effect
import slick.jdbc.JdbcBackend
import slick.sql.FixedSqlAction

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

object CardStream {

  def createCard()(implicit
      system: ActorSystem[_]
  ): Flow[BigDecimal, String, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val cardTable = TableQuery[CardTable]
    implicit val materializer = SystemMaterializer(system).materializer

    val insertCard: CardModel.CardInfo => DBIO[Int] =
      (card: CardModel.CardInfo) => cardTable += card

    Flow[BigDecimal]
      .map(money => (money, UUID.randomUUID().toString.replaceAll("-", "")))
      .via(
        Slick
          .flowWithPassThrough { tp2 =>
            (for {
              card: Int <- insertCard(
                CardModel.CardInfo(
                  id = tp2._2,
                  money = tp2._1,
                  openid = None,
                  activeTime = LocalDateTime.now(),
                  createTime = LocalDateTime.now()
                )
              )
            } yield tp2._2).transactionally
          }
      )
  }

  def updateCard()(implicit
      system: ActorSystem[_]
  ): Flow[CardModel.UpdateCard, Boolean, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val cardTable = TableQuery[CardTable]
    implicit val materializer = SystemMaterializer(system).materializer

    Slick.flowWithPassThrough { card =>
      (for {
        result <-
          cardTable
            .filter(_.id === card.id)
            .map(i => (i.openid, i.activeTime))
            .update((Some(card.openid), LocalDateTime.now()))
      } yield result == 1).transactionally
    }
  }

}
