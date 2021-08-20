package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.Flow
import com.dounine.douyinpay.behaviors.engine.QrcodeBehavior
import com.dounine.douyinpay.behaviors.engine.QrcodeBehavior.PaySuccess
import com.dounine.douyinpay.model.models.{AccountModel, CardModel}
import com.dounine.douyinpay.store.{AccountTable, CardTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.util.DingDing
import org.slf4j.LoggerFactory
import slick.jdbc.{JdbcBackend, StreamingInvokerAction}
import slick.sql.{FixedSqlAction, SqlStreamingAction}

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

object AccountStream {

  private val logger = LoggerFactory.getLogger(AccountStream.getClass)

  def decreaseVolumn()(implicit
      system: ActorSystem[_]
  ): Flow[QrcodeBehavior.Event, QrcodeBehavior.Event, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    Flow[QrcodeBehavior.Event]
      .mapAsync(1) {
        case e @ PaySuccess(request, sum) =>
          val openid = request.order.openid
          val volumn = request.order.volumn
          db.run(
              sqlu"update douyinpay_account set volumn = volumn - ${volumn} where openid = ${openid}"
            )
            .map(_ => {
              e
            })
        case other => Future.successful(other)
      }
  }

  def queryAccount()(implicit
      system: ActorSystem[_]
  ): Flow[String, Option[AccountModel.AccountInfo], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val accountTable = TableQuery[AccountTable]
    Flow[String]
      .mapAsync(1) { openid =>
        db.run(accountTable.filter(_.openid === openid).result.headOption)
      }
  }

  def incrmentMoneyToAccount()(implicit
      system: ActorSystem[_]
  ): Flow[AccountModel.AccountInfo, Boolean, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer
    Flow[AccountModel.AccountInfo]
      .mapAsync(1) { info =>
        db.run(
            sqlu"""INSERT INTO douyinpay_account VALUE(${info.openid},${info.money}) ON DUPLICATE KEY UPDATE money=money+${info.money}"""
          )
          .map(_ == 1)
      }
  }

}
