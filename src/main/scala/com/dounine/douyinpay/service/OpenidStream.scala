package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.Flow
import com.dounine.douyinpay.model.models.{CardModel, OpenidModel}
import com.dounine.douyinpay.store.{CardTable, OpenidTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import slick.jdbc.JdbcBackend

import java.time.LocalDateTime
import java.util.UUID
import scala.concurrent.{ExecutionContextExecutor, Future}

object OpenidStream {

  def autoCreateOpenidInfo()(implicit
      system: ActorSystem[_]
  ): Flow[OpenidModel.OpenidInfo, Boolean, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    val openidTable = TableQuery[OpenidTable]
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[OpenidModel.OpenidInfo]
      .mapAsync(1) { info =>
        db.run(openidTable.filter(_.openid === info.openid).result.headOption)
          .map(info -> _)
      }
      .mapAsync(1) { tp2 =>
        if (tp2._2.isEmpty) {
          db.run(openidTable += tp2._1)
            .map(_ == 1)
        } else if (tp2._2.get.locked) {
          throw new Exception("locked error")
        } else {
          Future.successful(false)
        }
      }
  }

}
