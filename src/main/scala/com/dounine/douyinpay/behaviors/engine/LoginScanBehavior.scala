package com.dounine.douyinpay.behaviors.engine

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, Behavior}
import com.dounine.douyinpay.model.models.BaseSerializer
import com.dounine.douyinpay.service.DictionaryService
import com.dounine.douyinpay.tools.akka.chrome.{Chrome, ChromePools}
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.ServiceSingleton
import org.openqa.selenium.remote.html5.RemoteWebStorage
import org.openqa.selenium.remote.{RemoteExecuteMethod, RemoteWebDriver}
import org.openqa.selenium.{Cookie, OutputType, WebElement}

import java.io.File
import java.time.LocalDateTime
import java.util
import java.util.UUID
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success}

object LoginScanBehavior extends JsonParse {

  sealed trait Command extends BaseSerializer

  final case class GetScan()(val replyTo: ActorRef[BaseSerializer])
      extends Command

  final case class ScanSuccess(url: String) extends BaseSerializer

  final case class ScanFail(msg: String) extends BaseSerializer

  final case class Query(time: LocalDateTime) extends Command

  final case class UpdateSuccess() extends Command

  final case class UpdateFail(msg: String) extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      val pools = ChromePools(context.system).pool(0)
      val resource = pools.borrowObject()
      val driver: RemoteWebDriver = resource.driver()
      val configService: DictionaryService =
        ServiceSingleton.instance(classOf[DictionaryService], context.system)

      val destroy: () => Unit = () => {
        driver.get(
          "https://www.douyin.com/falcon/webcast_openpc/pages/douyin_recharge/index.html?is_new_connect=0&is_new_user=0"
        )
        driver.quit()
      }

      Behaviors.withTimers(timers => {
        Behaviors.receiveMessage {
          case UpdateSuccess() =>
            context.log.info("UpdateSuccess")
            destroy()
            Behaviors.stopped
          case UpdateFail(msg) =>
            context.log.error("UpdateFail {}", msg)
            destroy()
            Behaviors.stopped
          case e @ GetScan() =>
            try {
              driver.get(
                "https://www.douyin.com/falcon/webcast_openpc/pages/douyin_recharge/index.html?is_new_connect=0&is_new_user=0"
              )
              try {
                val executeMethod = new RemoteExecuteMethod(driver)
                val webStorage = new RemoteWebStorage(executeMethod)
                webStorage.getLocalStorage.clear()
              } catch {
                case e: Throwable =>
              }
              try {
                val executeMethod = new RemoteExecuteMethod(driver)
                val webStorage = new RemoteWebStorage(executeMethod)
                driver.manage().deleteAllCookies()
              } catch {
                case e: Throwable =>
              }
              driver.navigate().refresh()
              val qrcodeImg: WebElement =
                driver.findElementByClassName("qrcode-img")
              if (qrcodeImg == null) {
                e.replyTo.tell(ScanFail("二维码不存在"))
                destroy()
                return Behaviors.stopped
              }
              TimeUnit.SECONDS.sleep(1)

              context.log.info(
                "src {}",
                driver
                  .findElementByClassName("qrcode-img")
                  .getAttribute("src")
                  .length
                  .toString
              )

              val file: File = driver
                .findElementByClassName("qrcode-img")
                .getScreenshotAs(OutputType.FILE)

              context.self.tell(Query(LocalDateTime.now()))
              e.replyTo.tell(ScanSuccess(file.getAbsolutePath))
              Behaviors.same
            } catch {
              case ee: Exception =>
                e.replyTo.tell(ScanFail(ee.getMessage))
                destroy()
                Behaviors.stopped
            }
          case Query(time) =>
            if (LocalDateTime.now().isAfter(time.plusSeconds(60))) {
              context.log.info("超时没有扫码")
              destroy()
              Behaviors.stopped
            } else {
              try {
                val userInfoEl: WebElement =
                  driver.findElementByClassName("user-info")
                if (userInfoEl != null) {
                  val executeMethod = new RemoteExecuteMethod(driver)
                  val webStorage = new RemoteWebStorage(executeMethod)

                  val localStoreages: util.Set[String] =
                    webStorage.getLocalStorage.keySet()
                  val localStorageMap: Map[String, String] =
                    localStoreages.asScala
                      .map(name => {
                        (
                          s"localStorage|${name}",
                          webStorage.getLocalStorage.getItem(name)
                        )
                      })
                      .toMap
                  val cookies: util.Set[Cookie] = driver.manage().getCookies
                  val cookieMap: Map[String, String] = cookies.asScala
                    .map(item => {
                      (
                        s"cookie|${item.getName}",
                        item.getValue
                      )
                    })
                    .toMap

                  context.pipeToSelf(
                    configService.upsert(
                      "douyin_cookie",
                      (localStorageMap ++ cookieMap).toJson
                    )
                  ) {
                    case Failure(exception) => UpdateFail(exception.getMessage)
                    case Success(_)         => UpdateSuccess()
                  }
                }
              } catch {
                case e: Exception =>
              }
              timers.startSingleTimer("query", Query(time), 3.seconds)
              Behaviors.same
            }
        }
      })

    }

}
