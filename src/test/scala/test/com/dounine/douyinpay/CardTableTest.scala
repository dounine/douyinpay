package test.com.dounine.douyinpay

import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.dounine.douyinpay.model.models.UserModel
import com.dounine.douyinpay.service.UserService
import com.dounine.douyinpay.store.{CardTable, EnumMappers, UserTable}
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.json.JsonParse
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import java.time.LocalDateTime
import scala.concurrent.Await
import scala.concurrent.duration._

class CardTableTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
           |akka.remote.artery.canonical.port = 25520
           |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          CardTableTest
        ].getSimpleName}"
           |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          CardTableTest
        ].getSimpleName}"
           |""".stripMargin)
        .withFallback(
          ConfigFactory.parseResources("application-test.conf")
        )
        .resolve()
    )
    with Matchers
    with AnyWordSpecLike
    with LogCapturing
    with EnumMappers
    with MockitoSugar
    with JsonParse {
  val sharding = ClusterSharding(system)

  val db = DataSource(system).source().db
  val dict = CardTable()

  def beforeFun(): Unit = {
    try {
      Await.result(db.run(dict.schema.dropIfExists), Duration.Inf)
    } catch {
      case e => {
        system.log.error(e.getMessage)
      }
    }
    Await.result(db.run(dict.schema.createIfNotExists), Duration.Inf)
  }

  def afterFun(): Unit = {
    Await.result(db.run(dict.schema.truncate), Duration.Inf)
    Await.result(db.run(dict.schema.dropIfExists), Duration.Inf)
  }

  val userService = new UserService()
  "card table test" should {
    "table create" in {
//      beforeFun()

//      afterFun()
    }
  }
}
