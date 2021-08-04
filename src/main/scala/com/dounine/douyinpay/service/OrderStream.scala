package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.SlickSession
import akka.stream.scaladsl.{Flow, Source}
import com.dounine.douyinpay.model.models.AccountModel
import com.dounine.douyinpay.store.{AccountTable, OrderTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import slick.jdbc.JdbcBackend

import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.{ExecutionContextExecutor, Future}

object OrderStream {

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
  ): Source[Int, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val orderTable = TableQuery[OrderTable]
    implicit val materializer = SystemMaterializer(system).materializer

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
  }

}
