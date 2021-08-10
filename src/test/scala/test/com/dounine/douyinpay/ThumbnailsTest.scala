package test.com.dounine.douyinpay

import akka.{Done, NotUsed}
import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.event.LogMarker
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.model.{
  HttpEntity,
  MediaTypes,
  StatusCode,
  StatusCodes,
  Uri
}
import akka.http.scaladsl.server.{RequestContext, RouteResult}
import akka.stream.{
  Attributes,
  ClosedShape,
  DelayOverflowStrategy,
  FlowShape,
  Materializer,
  OverflowStrategy,
  QueueCompletionResult,
  QueueOfferResult,
  RestartSettings,
  SinkShape,
  SourceShape,
  SystemMaterializer,
  ThrottleMode
}
import akka.stream.scaladsl.{
  Broadcast,
  Concat,
  DelayStrategy,
  Flow,
  GraphDSL,
  Keep,
  Merge,
  MergePreferred,
  OrElse,
  Partition,
  RestartSource,
  RunnableGraph,
  Sink,
  Source,
  SourceQueueWithComplete,
  Unzip,
  Zip,
  ZipWith
}
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.typed.scaladsl.ActorSource
import akka.util.ByteString
import com.dounine.douyinpay.behaviors.engine.QrcodeBehavior.logger
import com.dounine.douyinpay.model.models.{BaseSerializer, WechatModel}
import com.dounine.douyinpay.router.routers.errors.DataException
import com.dounine.douyinpay.store.EnumMappers
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.DingDing
import com.typesafe.config.ConfigFactory
import net.coobird.thumbnailator.Thumbnails
import net.coobird.thumbnailator.geometry.Positions
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar
import pdi.jwt.{Jwt, JwtAlgorithm, JwtClaim, JwtHeader}

import java.io.File
import java.time.{Clock, LocalDateTime}
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success, Try}

class ThumbnailsTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25520
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          ThumbnailsTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          ThumbnailsTest
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
    with MockitoSugar {

  implicit val ec = system.executionContext

  "ThumbnailsTest" should {

    "watermark" in {

      Thumbnails
        .of("images/a380_1280x1024.jpg")
        .size(1280, 1024)
        .watermark(
          Positions.BOTTOM_RIGHT,
          ImageIO.read(new File("images/watermark.png")),
          0.5f
        )
        .outputQuality(0.8f)
        .toFile("/tmp/a380_watermark_bottom_right.jpg");
    }
  }
}
