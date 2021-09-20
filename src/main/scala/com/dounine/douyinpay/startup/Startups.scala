package com.dounine.douyinpay.startup

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, Entity}
import akka.persistence.typed.PersistenceId
import akka.stream.scaladsl.Sink
import com.dounine.douyinpay.behaviors.engine.AccessTokenBehavior.InitToken
import com.dounine.douyinpay.behaviors.engine.{AccessTokenBehavior, JSApiTicketBehavior}
import com.dounine.douyinpay.model.models.UserModel
import com.dounine.douyinpay.service.{DictionaryService, OpenidStream, OrderService, OrderStream, UserService}
import com.dounine.douyinpay.store.{AccountTable, AkkaPersistenerJournalTable, AkkaPersistenerSnapshotTable, BreakDownTable, DictionaryTable, OpenidTable, OrderTable, PayTable, UserTable}
import com.dounine.douyinpay.tools.akka.chrome.ChromePools
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.util.{DingDing, LockedUsers, OpenidPaySuccess, ServiceSingleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.lifted

import java.time.LocalDateTime
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class Startups(implicit system: ActorSystem[_]) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[Startups])
  implicit val ec: ExecutionContextExecutor = system.executionContext
  val sharding: ClusterSharding = ClusterSharding(system)
  val pro = system.settings.config.getBoolean("app.pro")

  def start(): Unit = {
    import scala.jdk.CollectionConverters._
    val wechat = system.settings.config.getConfig("app.wechat")
    val appids = wechat.entrySet().asScala.map(_.getKey.split("\\.").head).toSet
    appids.foreach(appid => {
      sharding
        .init(
          Entity(
            typeKey = AccessTokenBehavior.typeKey
          )(
            createBehavior = entityContext => AccessTokenBehavior()
          )
        )
        .tell(
          ShardingEnvelope(
            appid,
            AccessTokenBehavior
              .InitToken(appid, wechat.getConfig(appid).getString("secret"))
          )
        )

      sharding
        .init(
          Entity(
            typeKey = JSApiTicketBehavior.typeKey
          )(
            createBehavior = entityContext => JSApiTicketBehavior()
          )
        )
        .tell(
          ShardingEnvelope(
            appid,
            JSApiTicketBehavior
              .InitTicket(appid, wechat.getConfig(appid).getString("secret"))
          )
        )
    })

    ServiceSingleton.put(classOf[OrderService], new OrderService())
    ServiceSingleton.put(classOf[UserService], new UserService())
    ServiceSingleton.put(
      classOf[DictionaryService],
      new DictionaryService()
    )
//    ChromePools(system).pools
//      .returnObject(ChromePools(system).pools.borrowObject())

    import slick.jdbc.MySQLProfile.api._
    val db = DataSource(system).source().db
    val schemas = Seq(
      UserTable().schema,
      OrderTable().schema,
      DictionaryTable().schema,
      PayTable().schema,
      AccountTable().schema,
      OpenidTable().schema,
      BreakDownTable().schema,
      AkkaPersistenerJournalTable().schema,
      AkkaPersistenerSnapshotTable().schema
    )
    schemas.foreach(schema => {
      try {
        Await.result(
          db.run(schema.createIfNotExists),
          Duration.Inf
        )
      } catch {
        case e: Throwable => {
          logger.error(e.getMessage)
        }
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
    OrderStream
      .queryOrdersSuccess()
      .runForeach(maps => {
        val list = maps
          .groupBy(_.openid)
          .map {
            case (openid, orders) =>
              openid -> (orders.length, orders.map(_.money).sum)
          }
        OpenidPaySuccess.init(list)
      })
    OpenidStream
      .queryLockeds()
      .runForeach(openids => {
        LockedUsers.init(openids)
      })
  }

  def httpAfter(): Unit = {
    DingDing.sendMessage(
      DingDing.MessageType.system,
      data = DingDing.MessageData(
        markdown = DingDing.Markdown(
          title = "系统通知",
          text = s"""
              |# 程序启动
              | - time: ${LocalDateTime.now()}
              |""".stripMargin
        )
      ),
      system
    )
  }

}
