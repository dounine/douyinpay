package com.dounine.douyinpay.tools.akka.chrome

import akka.actor.typed.ActorSystem
import akka.stream.{Materializer, SystemMaterializer}
import com.dounine.douyinpay.service.DictionaryService
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.ServiceSingleton
import com.typesafe.config.Config
import org.openqa.selenium.chrome.ChromeOptions
import org.openqa.selenium.remote.html5.RemoteWebStorage
import org.openqa.selenium.remote.{RemoteExecuteMethod, RemoteWebDriver}
import org.openqa.selenium.{Cookie, Dimension}
import org.slf4j.LoggerFactory

import java.net.URL
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContextExecutor, Future}

class Chrome(system: ActorSystem[_], hubUrl: String) extends JsonParse {

  private final val config: Config = system.settings.config.getConfig("app")

  val createTime = LocalDateTime.now()
  private val logger = LoggerFactory.getLogger(classOf[Chrome])
  implicit val materialize: Materializer = SystemMaterializer(
    system
  ).materializer
  val chromeOptions: ChromeOptions = new ChromeOptions()
  chromeOptions.setHeadless(config.getBoolean("selenium.headless"))
  val webDriver = new RemoteWebDriver(new URL(hubUrl), chromeOptions)
  this.webDriver
    .manage()
    .timeouts()
    .implicitlyWait(config.getInt("selenium.implicitlyWait"), TimeUnit.SECONDS)
  this.webDriver
    .manage()
    .window()
    .setSize(
      new Dimension(
        config.getInt("selenium.size.width"),
        config.getInt("selenium.size.height")
      )
    )
  private var chromeLive = true
  this.webDriver.get(
    "https://www.douyin.com/falcon/webcast_openpc/pages/douyin_recharge/index.html?is_new_connect=0&is_new_user=0"
  )

  val userCookieInfo = Await.result(
    ServiceSingleton
      .get(classOf[DictionaryService])
      .info("douyin_cookie"),
    Duration.Inf
  )
  userCookieInfo match {
    case Some(value) =>
      val maps: Map[String, String] = value.text.jsonTo[Map[String, String]]
      val executeMethod = new RemoteExecuteMethod(this.webDriver)
      val webStorage = new RemoteWebStorage(executeMethod)
      maps
        .filter(_._1.startsWith("localStorage|"))
        .foreach(item => {
          webStorage.getLocalStorage
            .setItem(item._1.substring("localStorage|".length), item._2)
        })
      maps
        .filter(_._1.startsWith("cookie|"))
        .foreach(item => {
          this.webDriver
            .manage()
            .addCookie(new Cookie(item._1.substring("cookie|".length), item._2))
        })

    case None =>
  }
  this.webDriver.navigate().refresh()

  def driver(cookieName: String): RemoteWebDriver = {
    this.webDriver
//    ServiceSingleton
//      .get(classOf[DictionaryService])
//      .info(cookieName)
//      .map {
//        case Some(cookie) => {
//          logger.info(s"chrome driver set cookie ${cookieName}")
//          val maps: Map[String, String] =
//            cookie.text.jsonTo[Map[String, String]]
//          val executeMethod = new RemoteExecuteMethod(this.webDriver)
//          val webStorage = new RemoteWebStorage(executeMethod)
//          maps
//            .filter(_._1.startsWith("localStorage|"))
//            .foreach(item => {
//              webStorage.getLocalStorage
//                .setItem(item._1.substring("localStorage|".length), item._2)
//            })
//          maps
//            .filter(_._1.startsWith("cookie|"))
//            .foreach(item => {
//              this.webDriver
//                .manage()
//                .addCookie(
//                  new Cookie(item._1.substring("cookie|".length), item._2)
//                )
//            })
//          this.webDriver.get(
//            "https://www.douyin.com/falcon/webcast_openpc/pages/douyin_recharge/index.html?is_new_connect=0&is_new_user=0"
//          )
//          this.webDriver
//        }
//        case None => {
//          logger.error(s"${cookieName} not found")
//          this.webDriver
//        }
//      }(system.executionContext)
  }
  def refresh(): Unit = {
    logger.info("chrome refresh")
    this.webDriver.get(
      "https://www.douyin.com/falcon/webcast_openpc/pages/douyin_recharge/index.html?is_new_connect=0&is_new_user=0"
    )
  }
  def driver(): RemoteWebDriver = this.webDriver

  def isLive(): Boolean = {
    this.chromeLive
  }

  def dead(): Unit = {
    this.chromeLive = false
  }

}
