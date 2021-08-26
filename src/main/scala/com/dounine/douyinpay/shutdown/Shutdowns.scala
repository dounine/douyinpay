package com.dounine.douyinpay.shutdown

import akka.Done
import akka.actor.CoordinatedShutdown
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import com.dounine.douyinpay.tools.akka.chrome.ChromePools
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.util.DingDing
import org.joda.time.LocalDateTime
import org.slf4j.LoggerFactory

import scala.concurrent.Future
import scala.concurrent.duration._
class Shutdowns(system: ActorSystem[_]) {
  implicit val ec = system.executionContext
  val sharding = ClusterSharding(system)
  val logger = LoggerFactory.getLogger(classOf[Shutdowns])
  val pro = system.settings.config.getBoolean("app.pro")

  def listener(): Unit = {
    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeDb") { () =>
        {
          Future {
            logger.info("db source close")
            DataSource(system)
              .source()
              .db
              .close()
            Done
          }
        }
      }

    CoordinatedShutdown(system)
      .addTask(CoordinatedShutdown.PhaseBeforeServiceUnbind, "closeChrome") {
        () =>
          {
            Future {
              logger.info("chrome source close")
              (0 until ChromePools(system).poolSize()).foreach(id => {
                ChromePools(system).pool(id).close()
              })
              Done
            }
          }
      }

    CoordinatedShutdown(system).addJvmShutdownHook(() => {
      if (pro) {
        DingDing.sendMessage(
          DingDing.MessageType.system,
          data = DingDing.MessageData(
            markdown = DingDing.Markdown(
              title = "系统通知",
              text = s"""
                        |# 程序停止
                        | - time: ${LocalDateTime.now()}
                        |""".stripMargin
            )
          ),
          system
        )
      }
    })

  }

}
