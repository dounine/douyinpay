package com.dounine.douyinpay.service

import akka.actor.typed.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.dounine.douyinpay.model.models.DictionaryModel
import com.dounine.douyinpay.model.types.service.PayPlatform.PayPlatform
import com.dounine.douyinpay.store.{DictionaryTable, EnumMappers}
import com.dounine.douyinpay.tools.akka.db.DataSource
import io.circe.syntax.KeyOps
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.MySQLProfile
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import java.time.LocalDateTime
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContextExecutor, Future}

class DictionaryService(implicit system: ActorSystem[_]) extends EnumMappers {

  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val ec: ExecutionContextExecutor = materializer.executionContext
  private val db = DataSource(system).source().db
  private val dict = DictionaryTable()

  def upsert(key: String, value: String): Future[Int] =
    db.run(
      dict.insertOrUpdate(
        DictionaryModel.DbInfo(
          key = key,
          text = value,
          createTime = LocalDateTime.now()
        )
      )
    )

  def info(
      key: String
  ): Future[Option[DictionaryModel.DbInfo]] =
    db.run(dict.filter(_.key === key).result.headOption)
}
