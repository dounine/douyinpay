package com.dounine.douyinpay.startup

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.persistence.typed.PersistenceId
import com.dounine.douyinpay.behaviors.engine.{CoreEngine, OrderSources, QrcodeSources}
import com.dounine.douyinpay.model.models.UserModel
import com.dounine.douyinpay.service.{DictionaryService, OrderService, UserService}
import com.dounine.douyinpay.store.{AkkaPersistenerJournalTable, AkkaPersistenerSnapshotTable, DictionaryTable, OrderTable, UserTable}
import com.dounine.douyinpay.tools.akka.chrome.ChromePools
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.util.{DingDing, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.lifted

import java.time.LocalDateTime
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class Startups(system: ActorSystem[_]) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[Startups])
  implicit val ec: ExecutionContextExecutor = system.executionContext
  val sharding: ClusterSharding = ClusterSharding(system)

  def start(): Unit = {
    sharding.init(
      Entity(
        typeKey = QrcodeSources.typeKey
      )(
        createBehavior = entityContext =>
          QrcodeSources(
            PersistenceId.of(
              QrcodeSources.typeKey.name,
              entityContext.entityId
            )
          )
      )
    )

    ServiceSingleton.put(classOf[OrderService], new OrderService(system))
    ServiceSingleton.put(classOf[UserService], new UserService(system))
    ServiceSingleton.put(
      classOf[DictionaryService],
      new DictionaryService(system)
    )
//    ChromePools(system).pools
//      .returnObject(ChromePools(system).pools.borrowObject())

    import slick.jdbc.MySQLProfile.api._
    val db = DataSource(system).source().db
    val schemas = Seq(
      lifted.TableQuery[UserTable].schema,
      lifted.TableQuery[OrderTable].schema,
      lifted.TableQuery[DictionaryTable].schema,
      lifted.TableQuery[AkkaPersistenerJournalTable].schema,
      lifted.TableQuery[AkkaPersistenerSnapshotTable].schema
    )
    schemas.foreach(schema => {
      try {
        Await.result(
          db.run(schema.createIfNotExists),
          Duration.Inf
        )
      } catch {
        case e: Throwable =>
      }
    })
    ServiceSingleton
      .get(classOf[UserService])
      .add(
        UserModel.DbInfo(
          apiKey = "4229d691b07b13341da53f17ab9f2416",
          apiSecret = "900150983cd24fb0d6963f7d28e17f72",
          balance = BigDecimal("0.00"),
          margin = BigDecimal("0.00"),
          callback = Option.empty,
          createTime = LocalDateTime.now()
        )
      )
      .onComplete {
        case Failure(exception) =>
          logger.error(exception.getMessage)
        case Success(value) =>
          logger.info(s"insert user apikey result ${value}")
      }

    ServiceSingleton
      .get(classOf[UserService])
      .add(
        UserModel.DbInfo(
          apiKey = "lake",
          apiSecret = "lake",
          balance = BigDecimal("10.00"),
          margin = BigDecimal("0.00"),
          callback = Option.empty,
          createTime = LocalDateTime.now()
        )
      )
      .onComplete {
        case Failure(exception) => {
          logger.error(exception.getMessage)
        }
        case Success(value) =>
          logger.info(s"insert user apikey result ${value}")
      }


  }

  def httpAfter(): Unit = {
    DingDing.sendMessage(
      DingDing.MessageType.system,
      data = DingDing.MessageData(
        markdown = DingDing.Markdown(
          title = "系统通知", text =
            s"""
              |# 程序启动
              | - time: ${LocalDateTime.now()}
              |""".stripMargin
        )
      ),
      system
    )
//    sharding
//      .entityRefFor(
//        CoreEngine.typeKey,
//        CoreEngine.typeKey.name
//      )
//      .ask(
//        CoreEngine.Message(
//          OrderSources.QueryOrderInit()
//        )
//      )(3.seconds)
  }

}
