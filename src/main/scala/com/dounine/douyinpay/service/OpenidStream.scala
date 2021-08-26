package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.Flow
import com.dounine.douyinpay.model.models.{CardModel, OpenidModel}
import com.dounine.douyinpay.model.types.service.LogEventKey
import com.dounine.douyinpay.store.{CardTable, OpenidTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.json.JsonParse
import org.slf4j.LoggerFactory
import slick.jdbc.JdbcBackend

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

object OpenidStream extends JsonParse {

  private val logger = LoggerFactory.getLogger(OpenidStream.getClass)

  def autoCreateOpenidInfo()(implicit
      system: ActorSystem[_]
  ): Flow[OpenidModel.OpenidInfo, Boolean, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[OpenidModel.OpenidInfo]
      .mapAsync(1) { info =>
        db.run(OpenidTable().filter(_.openid === info.openid).result.headOption)
          .map(info -> _)
      }
      .mapAsync(1) { tp2 =>
        if (tp2._2.isEmpty) {
          db.run(OpenidTable() += tp2._1)
            .map(_ == 1)
        } else {
          Future.successful(false)
        }
      }
  }

  def query()(implicit
      system: ActorSystem[_]
  ): Flow[String, Option[OpenidModel.OpenidInfo], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(OpenidTable().filter(_.openid === openid).result.headOption)
      }
  }

}
