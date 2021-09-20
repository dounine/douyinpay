package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.{Flow, Source}
import com.dounine.douyinpay.model.models.{BreakDownModel, UserModel}
import com.dounine.douyinpay.store.{BreakDownTable, UserTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import slick.jdbc.JdbcBackend

import java.time.LocalDateTime
import scala.concurrent.ExecutionContextExecutor

object BreakDownStream {

  def notSuccess()(implicit
      system: ActorSystem[_]
  ): Source[BreakDownModel.BreakDownInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._

    Slick.source(
      BreakDownTable()
        .filter(i =>
          i.success === false && i.createTime > LocalDateTime
            .now()
            .minusDays(3)
        )
        .result
    )
  }

  def openidAndAccountLatest()(implicit
      system: ActorSystem[_]
  ): Flow[(String, String), Option[BreakDownModel.BreakDownInfo], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._

    Flow[(String, String)]
      .mapAsync(1) { tp2 =>
        db.run(
          BreakDownTable()
            .filter(i =>
              i.success === false && i.openid === tp2._1 && i.account === tp2._2 && i.createTime >= LocalDateTime
                .now()
                .minusDays(3)
            )
            .result
            .headOption
        )
      }
  }

  def add()(implicit
      system: ActorSystem[_]
  ): Flow[
    BreakDownModel.BreakDownInfo, {
      val info: BreakDownModel.BreakDownInfo
      val result: Boolean
    },
    NotUsed
  ] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    Flow[BreakDownModel.BreakDownInfo]
      .mapAsync(1) { info =>
        db.run(
            BreakDownTable() += info
          )
          .map(i =>
            new {
              val info: BreakDownModel.BreakDownInfo = info
              val result: Boolean = i == 1
            }
          )
      }
  }

  def success()(implicit
      system: ActorSystem[_]
  ): Flow[String, Boolean, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    Flow[String]
      .mapAsync(1) { id =>
        db.run(
            BreakDownTable()
              .filter(_.id === id)
              .map(_.success)
              .update(true)
          )
          .map(_ == 1)
      }
  }

}
