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
  ): Flow[CardModel.CardInfo, CardModel.CardInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val cardTable = CardTable()

    val insertCard: CardModel.CardInfo => DBIO[Int] =
      (card: CardModel.CardInfo) => cardTable += card

    Flow[CardModel.CardInfo]
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

  def cardPayed()(implicit
      system: ActorSystem[_]
  ): Flow[CardModel.UpdateCard, (CardModel.UpdateCard, Int), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val cardTable = CardTable()

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
