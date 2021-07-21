package test.com.dounine.douyinpay

import akka.{Done, NotUsed}
import akka.actor.testkit.typed.scaladsl.{
  LogCapturing,
  ScalaTestWithActorTestKit
}
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.Behaviors
import akka.event.LogMarker
import akka.http.scaladsl.model.{HttpEntity, MediaTypes}
import akka.stream.{
  Attributes,
  ClosedShape,
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
import com.dounine.douyinpay.model.models.BaseSerializer
import com.dounine.douyinpay.router.routers.errors.DataException
import com.dounine.douyinpay.store.EnumMappers
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.DingDing
import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatestplus.mockito.MockitoSugar

import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object QrcodeTest {
  def apply(): Behavior[String] = {
    Behaviors.setup { context =>
      {
        val materializer = Materializer(context)
        Source(1 to 10)
          .throttle(1, 1.seconds)
          .runForeach(println)(materializer)
        Behaviors.receiveMessage {
          case "stop" => {
            println("stop")
            Behaviors.stopped
          }
          case "hello" => {
            println("hello")
            Behaviors.same
          }
        }
      }
    }
  }

}
case class HelloSet(name: String, age: Int) {
  override def hashCode() = name.hashCode

  override def equals(obj: Any) = {
    if (obj == null) {
      false
    } else {
      obj.asInstanceOf[HelloSet].name == name
    }
  }
}
sealed trait StreamEvent
sealed trait AppEvent extends StreamEvent
case class AppOnline() extends AppEvent
case class AppOffline() extends AppEvent
sealed trait OrderEvent extends StreamEvent
case class OrderInit() extends OrderEvent
case class OrderRequest() extends OrderEvent
case class OrderRequestOk() extends OrderEvent

object FlowLog {
  implicit class FlowLog(data: Flow[StreamEvent, StreamEvent, NotUsed])
      extends JsonParse {
    def log(name: String): Flow[StreamEvent, StreamEvent, NotUsed] = {
      data
        .logWithMarker(
          s" ******************* ",
          (e: StreamEvent) =>
            LogMarker(
              name = s"${name}Marker"
            ),
          (e: StreamEvent) => e.logJson
        )
        .withAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Error
          )
        )

    }
  }
}

class StreamForOptimizeTest
    extends ScalaTestWithActorTestKit(
      ConfigFactory
        .parseString(s"""
                      |akka.remote.artery.canonical.port = 25520
                      |akka.persistence.journal.leveldb.dir = "/tmp/journal_${classOf[
          StreamForOptimizeTest
        ].getSimpleName}"
                      |akka.persistence.snapshot-store.local.dir = "/tmp/snapshot_${classOf[
          StreamForOptimizeTest
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

  "stream optimize" should {

    "test concat and child source merge" ignore {
      println()
      Source
        .single(1)
        .flatMapConcat(_ => {
          Source
            .single(2)
            .merge(
              Source.single(3)
            )
        })
        .runForeach(println)
    }

    "source from actor" ignore {
      val (actor, source) = Source
        .actorRef[Int](
          completionMatcher = PartialFunction.empty,
          failureMatcher = PartialFunction.empty,
          bufferSize = 1,
          overflowStrategy = OverflowStrategy.dropNew
        )
        .preMaterialize()

      Array("a", "b", "c", "d", "e", "f", "g").foreach(i => {
        actor
        TimeUnit.MILLISECONDS.sleep(100)
      })

    }

    "graph from chrome and order" ignore {
      val (chromeQueue, chromeSources) =
        Source.queue[Int](1).preMaterialize()

      val (orderQueue, orderSources) =
        Source.queue[String](2).preMaterialize()

      RunnableGraph
        .fromGraph(GraphDSL.create() {
          implicit builder: GraphDSL.Builder[NotUsed] =>
            {
              import GraphDSL.Implicits._

              val chromes = builder.add(chromeSources)
              val orders = builder.add(orderSources)
              val zip =
                builder.add(ZipWith((left: Int, right: String) => right))
//              val broadcast = builder.add(Broadcast[String](2))
              val core = builder
                .add(
                  Flow[String]
                    .map(f => {
                      println("core", f)
                      f
                    })
                    .addAttributes(Attributes.inputBuffer(1, 1))
                )
//              val merge = builder.add(Merge[Int](2, false))
              val sink = builder.add(Sink.foreach[Any](f => {
                println("println", f)
              }))

              chromes ~> zip.in0
              orders ~> zip.in1
              zip.out ~> core ~> sink

//              chromes ~> merge.in(0)
//              orders ~> zip.in1
//              merge.out ~> zip.in0
//
//              zip.out ~> core ~> broadcast
//
//              broadcast.out(0) ~> sink
//              broadcast.out(1) ~> Flow[String].collect {
//                case "finish" => 1
//              } ~> merge.in(1)

              ClosedShape
            }
        })
        .run()

//      println(chromeQueue.offer(1))
      Array("a", "b", "c", "d", "e", "f", "g").foreach(i => {
        println("--", orderQueue.offer(i))
        TimeUnit.MILLISECONDS.sleep(100)
      })
    }

    "source zip" ignore {
      val source = Source(1 to 3)
        .throttle(1, 1.seconds)

      val source2 = Source("a" :: "b" :: "c" :: Nil)

      source
        .zip(source2)
        .runForeach(f => {
          println(f)
        })

      TimeUnit.SECONDS.sleep(4)
    }

    "queue 2" ignore {
      val queue = Source
        .queue[Int](1, OverflowStrategy.dropNew)
        //        .throttle(1, 3.second, 0, ThrottleMode.Shaping)
        .throttle(1, 3.seconds)
        .toMat(Sink.foreach(x => {
          println(s"completed $x")
        }))(Keep.left)
        .run()

      val source = Source(1 to 10)
      val result = source
        .mapAsync(1)(x => {
          queue.offer(x).map {
            case result: QueueCompletionResult =>
              println("QueueCompletionResult")
            case QueueOfferResult.Enqueued => println("Enqueued")
            case QueueOfferResult.Dropped  => println("Dropped")
          }
        })
        .runWith(Sink.ignore)

      println(result.futureValue)
    }

    "queue3" in {

      val (chromeQueue, chromeSource) = Source.queue[Int](1).preMaterialize()
      val flow = Flow.fromGraph(GraphDSL.create() { implicit builder =>
        {
          import GraphDSL.Implicits._
          val source = builder.add(chromeSource)
          val merge = builder.add(Merge[String](1))
          val zip = builder.add(ZipWith((left: String, right: Int) => left))
          merge.out ~> zip.in0
          source ~> zip.in1

          FlowShape(merge.in(0), zip.out)
        }
      })

      val flow2 = Flow[String]
        .map(i => i)

      val queue = Source
        .queue[String](1)
        .zipWith(chromeSource)((left, right) => left)
        .via(flow2)
//        .via(Flow[String].mapAsync(1) { i =>
//          {
//            var success = Promise[String]()
//            if (i == "2") {
//              chromeQueue.offer(1)
//              system.scheduler.scheduleOnce(
//                1.seconds,
//                () => {
//                  success.success(i)
//                }
//              )
//            } else {
//              success.success(i)
//            }
//            success.future
//          }
//        })
        .to(Sink.ignore)
        .run()
      chromeQueue.offer(1)
      TimeUnit.SECONDS.sleep(1)
      println(queue.offer("a"))
      TimeUnit.SECONDS.sleep(3)
      println(queue.offer("a"))
//      (0 to 10).foreach(i => {
//        println(queue.offer(i.toString))
//        TimeUnit.MILLISECONDS.sleep(500)
//      })
    }

    "source queue test" ignore {
      val (chromeQueue, chromeSources) =
        Source
          .queue[Int](1, OverflowStrategy.dropNew)
          .preMaterialize()

      val (orderQueue, orderSources) =
        Source
          .queue[String](1, OverflowStrategy.dropNew)
          .preMaterialize()

      orderSources
        .zip(chromeSources)
        .to(Sink.ignore)
        .run()

      var count = 0
      Source
        .repeat("a")
        .take(50)
        .mapAsync(1) { i =>
          orderQueue.offer(i).map {
            case result: QueueCompletionResult => println("completion")
            case QueueOfferResult.Enqueued => {
              count += 1
              println("enqueued")
            }
            case QueueOfferResult.Dropped => println("dropped")
          }
        }
        .runWith(Sink.ignore)
        .futureValue

      println(count)
//        .runForeach(i => {
////          println(i, chromeQueue.offer(i._1))
//          //回收
//        })

//      println("chrome", chromeQueue.offer(1))
//      println("chrome", chromeQueue.offer(2))
//      println("chrome", chromeQueue.offer(3))
//      println("chrome", chromeQueue.offer(4))

//      Source(Seq("a", "b", "c", "d", "e", "f", "g"))
//        .mapAsync(1) { i =>
//          orderQueue.offer(i).map {
//            case result: QueueCompletionResult => println("completion")
//            case QueueOfferResult.Enqueued     => println("enqueued")
//            case QueueOfferResult.Dropped      => println("dropped")
//          }
//        }
//        .runWith(Sink.ignore)
//      chromeQueue.offer(1)
//      println("order", orderQueue.offer("h").futureValue)
//      TimeUnit.SECONDS.sleep(1)
    }

    "stream error divertTo" ignore {

      val validFlow: Flow[Int, Either[Exception, Int], NotUsed] =
        Flow[Int].map(i => {
          if (i == 1) {
            Left(new Exception("valid error"))
          } else {
            Right(i)
          }
        })
      val persistenceFlow: Flow[Int, Either[Exception, Int], NotUsed] =
        Flow[Int]
          .mapAsync(1) { i =>
            Future {
              if (i == 2) {
                Left(new Exception("persistence error"))
              } else Right(i)
            }
          }
      val engineFlow: Flow[Int, Either[Exception, Int], NotUsed] = Flow[Int]
        .mapAsync(1) { i =>
          Future {
            if (i == 1) {
              Right(i)
            } else Left(new Exception("engine error"))
          }
        }

      val source = Source.fromGraph(GraphDSL.create() { implicit builder =>
        {
          import GraphDSL.Implicits._

          val source = builder.add(Source.single(3))
          val valid = builder.add(validFlow)
          val partition = builder.add(
            Partition[Either[Throwable, Int]](
              2,
              {
                case Left(value)  => 1
                case Right(value) => 0
              }
            )
          )
          val persistence = builder.add(persistenceFlow)
          val partitionPersistence =
            builder.add(Flow[Either[Throwable, Int]].collect {
              case Right(value) => value
            })
//          val engine = builder.add(engineFlow)
//          val broadcast = builder.add(Broadcast[Int](4))
          val output = builder.add(Concat[Either[Throwable, Int]](2))

          /**
            * source ~~~~~> valid ~~~~~~> persistence ~~~~~> output
            */
          source ~> valid ~> partition
          partition.out(0) ~> partitionPersistence ~> persistence ~> output.in(
            0
          )

          /**
            * valid ~~~> filter error ~~~~> output
            */
          partition.out(1) ~> Flow[Either[Throwable, Int]].collect {
            case e @ Left(value) => e
          } ~> output.in(1)

//          valid.collect {
//            case Right(value) => value
//          } ~> persistence
//          valid.collect{
//            case Left(value) => value
//          } ~> output
          SourceShape(output.out)
        }
      })

      source.runForeach {
        case Left(value)  => info(value.toString)
        case Right(value) => info(value.toString)
      }.futureValue shouldBe Done

    }

    "flow via err test" ignore {
      val source = Source.single(1)
      val validingFlow: Flow[Int, Int, NotUsed] = Flow[Int]
        .map(i => {
          if (i == 1) {
            throw new DataException("hello")
          } else Right(i)
        })
        .collect {
          case Right(i) => i
        }

      val persistenceFlow = Flow[Int]
        .map(i => {
          println(s"come in ${i}")
          i
        })

      source
        .via(validingFlow)
        .via(persistenceFlow)
        .runWith(Sink.head)
        .futureValue shouldBe 1

    }

    "http entity" ignore {
      val data = Source
        .single("""{"code":"ok"}""")
        .map(ByteString.apply)
      HttpEntity(
        contentType = MediaTypes.`application/json`,
        data = data
      )
    }

    "stream collect" ignore {
      Source(1 to 3)
        .collect {
          case 1 => 1
        }
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1)
    }

    "dingding" ignore {
      DingDing.sendMessage(
        DingDing.MessageType.system,
        data = DingDing.MessageData(
          markdown = DingDing.Markdown(
            title = "系统通知",
            text = s"""
                |# 这是支持markdown的文本
                | - apiKey: hello
                | - account: hi
                | - money: 6
                |
                |time -> ${LocalDateTime.now()}
                |""".stripMargin
          )
        ),
        system
      )

      TimeUnit.SECONDS.sleep(1)
    }

    "graph single repeat" ignore {
      val source = Source(1 to 3)
      RunnableGraph
        .fromGraph(GraphDSL.create() { implicit builder =>
          import GraphDSL.Implicits._

//          val merge = builder.add(Merge[Int](2))
          val zip = builder.add(ZipWith((left: Int, right: Int) => {
            println(s"zip -> ${left}:${right}")
            left
          }))
          val broadcast = builder.add(Broadcast[Int](2))
          val start = Source.single(0)
          val concat = builder.add(Concat[Int]())

          source ~> zip.in0
          zip.out ~> Flow[Int]
            .throttle(1, 1.seconds)
            .map(i => {
              println(i); i * 10
            }) ~> broadcast ~> Sink.foreach[Int](i => {
            println(s"result -> ${i}")
          })
          zip.in1 <~ concat <~ start
          concat <~ broadcast

//          source ~> merge ~> Flow[Int]
//            .throttle(1, 1.seconds)
//            .map(i => { println(i); i }) ~> broadcast
//          merge <~ Flow[Int].buffer(2, OverflowStrategy.fail) <~ broadcast

          ClosedShape
        })
        .run()

      TimeUnit.SECONDS.sleep(30)
    }

    "source concat" ignore {

      val a = Source(1 to 3)
      val b = Source(7 to 9)
        .throttle(1, 10.seconds)

      a.merge(b).runForeach(i => { info(i.toString) })
      TimeUnit.SECONDS.sleep(4)
    }

    "graph dsl" ignore {

      import FlowLog.FlowLog

      val flowApp = Flow[StreamEvent]
        .collectType[AppEvent]
        .statefulMapConcat { () =>
          {
            case AppOnline()  => Nil
            case AppOffline() => Nil
          }
        }

      val flowOrder = Flow[StreamEvent]
        .collectType[OrderEvent]
        .log("order")
        .statefulMapConcat { () =>
          {
            case OrderInit() => {
              OrderRequest() :: Nil
            }
            case OrderRequestOk() => Nil
            case OrderRequest() => {
              println("error")
              Nil
            }
          }
        }
        .log("order")
        .mapAsync(1) {
          case OrderRequest() => Future.successful(OrderRequestOk())
          case ee             => Future.successful(ee)
        }

      val (
        queue: SourceQueueWithComplete[StreamEvent],
        source: Source[StreamEvent, NotUsed]
      ) = Source
        .queue[StreamEvent](8, OverflowStrategy.backpressure)
        .preMaterialize()
      val graph = RunnableGraph.fromGraph(GraphDSL.create(source) {
        implicit builder: GraphDSL.Builder[NotUsed] =>
          (request: SourceShape[StreamEvent]) =>
            {
              import GraphDSL.Implicits._

              val broadcast = builder.add(Broadcast[StreamEvent](2))
              val merge = builder.add(Merge[StreamEvent](3))
              //                                         ┌───────┐
              //                               ┌────────▶□  app  □──┐
              //                               │         └───────┘  │
              //         ┌─────────┐     ┌─────□─────┐              │
              //         │  merge  □  ─▶ □ broadcast │              │
              //         └──□─□─□──┘     └─────□─────┘              │
              //            ▲ ▲ ▲              │         ┌───────┐  │
              //            │ │ │              └────────▶□ order │  │
              //┌─────────┐ │ │ │                        └───□───┘  │
              //│ request □─┘ │ └────────────────────────────┘      │
              //└─────────┘   └─────────────────────────────────────┘

              request ~> merge ~> broadcast ~> flowApp ~> merge.in(1)
              broadcast ~> flowOrder ~> merge.in(2)

              ClosedShape
            }

      })
      graph.run()
      queue.offer(OrderInit())
      TimeUnit.SECONDS.sleep(1)
    }

    "stream split" ignore {
      Source(1 to 3)
        .splitWhen(_ => true)
        .map(i => {
          println("流1 -> " + i)
          i
        })
        .mergeSubstreams
        .runForeach(i => {
          info(i.toString)
        })
      TimeUnit.SECONDS.sleep(1)
    }

    "test staful" ignore {
      val (
        (
          queue: SourceQueueWithComplete[String],
          shutdown: Promise[Option[Nothing]]
        ),
        source: Source[String, NotUsed]
      ) =
        Source
          .queue[String](
            bufferSize = 8,
            overflowStrategy = OverflowStrategy.backpressure
          )
          .concatMat(Source.maybe)(Keep.both)
          .preMaterialize()

      queue.offer("init")

      source
        .logWithMarker(
          " **************** ",
          (e: String) =>
            LogMarker(
              name = "marker"
            ),
          (e: String) => e
        )
        .addAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Error
          )
        )
        .via(
          Flow[String]
            .flatMapConcat {
              case "query" => Source.single("queryOk")
              case ee      => Source.single(ee)
            }
            .statefulMapConcat { () =>
              {
                case "init" => "query" :: Nil
//                case "query"     => "queryOk" :: Nil
                case "queryOk"   => Nil
                case "queryFail" => Nil
                case "ignore"    => Nil
                case ee          => ee :: Nil
              }
            }

          //            .mapAsync(1) {
          //              case "query" => Future.successful("queryOk")
          //              case ee      => Future.successful(ee)
          //            }
          //            .flatMapConcat{
//              case "2" => Source.single("4")
//              case ee => Source.single(ee)
//            }
        )
        .runForeach(result => {
          println("---------- >" + result)
          queue.offer(result)
//          if (result == "3") {
//            shutdown.success(None)
//          }
        })
//        .futureValue shouldBe Done

      TimeUnit.SECONDS.sleep(1)
      shutdown.trySuccess(None)
    }

    "set case class equals" ignore {
      val list = Set(HelloSet("a", 1), HelloSet("b", 2), HelloSet("a", 2))
      list.size shouldBe 2
    }

    "log marker" ignore {
      Source(1 to 3)
        .log("payStream")
        .addAttributes(
          attr = Attributes.logLevels(
            onElement = Attributes.LogLevels.Info
          )
        )
        .runForeach(i => {
          info(i.toString)
        })
        .futureValue shouldBe Done
    }
    "staful test " ignore {
      Source
        .single(1)
        .statefulMapConcat {
          () =>
            { el =>
              Array(1, 2, 3, 4)
            }
        }
        .runWith(Sink.seq)
        .futureValue shouldBe Seq(1, 2, 3, 4)

    }

    "source form actor" ignore {
//      val ref = ActorSource.actorRefWithBackpressure[String,String](
//        completionMatcher = {
//          case "finish" =>
//        },
//        failureMatcher = {
//          case "error" => new Exception("error")
//        },
//        bufferSize = 2,
//        overflowStrategy = OverflowStrategy.backpressure
//      )
//        .to(Sink.foreach(i => {
//          TimeUnit.SECONDS.sleep(3)
//          println(i)
//        }))
//        .run()
//
//      ref ! "hello"
//      ref ! "hello"
//      ref ! "hello"
//      ref ! "hello"
    }

    "actor and source test" ignore {
      val behavior = system.systemActorOf(QrcodeTest(), "hello")
      behavior.tell("hello")
      TimeUnit.SECONDS.sleep(2)
      behavior.tell("stop")
      TimeUnit.SECONDS.sleep(15)
    }

    "multi source terminal" ignore {
      Source
        .single(1)
        .map(i => i)
        .flatMapConcat { source =>
          Source(1 to 10)
        }
        .watchTermination()((pv, future) => {
          future.foreach(_ => {
            info("done")
          })
          pv
        })
        .logWithMarker(
          "mystream",
          e =>
            LogMarker(
              name = "myMarker",
              properties = Map("element" -> e)
            )
        )
        .addAttributes(
          Attributes.logLevels(
            onElement = Attributes.LogLevels.Info
          )
        )
        .runWith(Sink.last)
        .futureValue shouldBe 2
    }

    "finish error" ignore {
      Source
        .single(1)
        .map(f => {
          if (f == 1) {
            throw new Exception("error")
          } else f
        })

      val cc = Source(1 to 5)
        .watchTermination()((prevMatValue, future) => {
          future.onComplete {
            case Failure(exception) => println(exception.getMessage)
            case Success(_)         => println(s"The stream materialized $prevMatValue")
          }
        })

    }

    "error throw" ignore {
      Source(1 to 3)
        .mapAsync(1) { i =>
          Future {
            if (i == 1) {
              throw new Exception("error")
            } else i
          }.recover {
            case _ => throw new Exception("error1")
          }
        }
        .mapAsync(1) { i =>
          Future {
            if (i == 1) {
              throw new Exception("erro2")
            }
          }.recover {
            case _ => throw new Exception("error3")
          }
        }
        .recover {
          case e => {
            println(e.getMessage)
          }
        }
        .runForeach(println)
    }

    "future transform" ignore {
      Source
        .single(1)
        .mapAsync(1) { i =>
          Future {
            if (i == 1) {
              throw new Exception("error")
            } else i
          }.recover {
            case _ => throw new Exception("无法点击按钮")
          }
        }
        .recover {
          case e => e.getMessage == "无法点击按钮"
        }
        .runWith(Sink.head)
        .futureValue shouldBe true
    }
    "map error" ignore {
      val result = Source(1 to 3)
        .map(i => {
          if (i == 1) {
            throw new Exception("error")
          } else i
        })
        .mapError {
          case e: Exception => throw new RuntimeException("第一层错误")
        }
        .map(_ * 2)
        .mapError {
          case e: Exception => new RuntimeException("第二层错误")
        }
        .runWith(Sink.head)
        .futureValue

      info(result.toString)
    }
    "either test" ignore {
      Source
        .single("success")
        .delay(500.milliseconds)
        .map(Right.apply)
        .idleTimeout(200.milliseconds)
        .recover {
          case e: Throwable => Left(e)
        }
        .runWith(Sink.head)
        .futureValue match {
        case Left(value)  => info(value.getMessage)
        case Right(value) => info(value)
      }
    }
  }
}
