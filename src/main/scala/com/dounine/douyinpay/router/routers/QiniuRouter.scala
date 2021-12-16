package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.cluster.{Cluster, MemberStatus}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.directives.FileInfo
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.dounine.douyinpay.model.models.MessageDing
import com.dounine.douyinpay.tools.util.DingDing.MessageData
import com.dounine.douyinpay.tools.util.{DingDing, IpUtils, Request}
import com.qiniu.http.Response
import com.qiniu.storage.{Configuration, Region, UploadManager}
import com.qiniu.util.Auth

import java.io.File
import java.time.LocalDate
import scala.concurrent.duration._

class QiniuRouter()(implicit system: ActorSystem[_]) extends SuportRouter {
  val cluster: Cluster = Cluster.get(system)
  val config = system.settings.config.getConfig("app")
  val fileSaveDirectory = config.getString("file.directory")

  def tempDestination(fileInfo: FileInfo): File = {
    val directory = new File(fileSaveDirectory + "/" + LocalDate.now())
    directory.mkdirs()
    File.createTempFile(
      "qiniu_",
      "_" + fileInfo.getFileName,
      directory
    )
  }

  val route =
    cors() {
      concat(
        post {
          path("qiniu") {
            parameter("key") {
              key =>
                storeUploadedFile("file", tempDestination) {
                  case (metadata, file) => {
                    val ACCESS_KEY =
                      config.getString(
                        "qiniu.key"
                      )
                    val SECRET_KEY =
                      config.getString(
                        "qiniu.secret"
                      )
                    val bucket = config.getString("qiniu.bucket")
                    val auth = Auth.create(ACCESS_KEY, SECRET_KEY)
                    val upToken = auth.uploadToken(bucket, key)
                    val uploadManager =
                      new UploadManager(
                        new Configuration(Region.autoRegion())
                      )
                    val response: Response = uploadManager.put(
                      file,
                      key,
                      upToken
                    )
                    ok(Map("result" -> response.toString))
                  }
                }
            }
          }
        }
      )
    }
}
