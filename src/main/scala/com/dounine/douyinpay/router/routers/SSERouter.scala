package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.sse.ServerSentEvent
import akka.http.scaladsl.server.Directives._
import akka.stream.scaladsl.Source

import java.time.LocalDateTime
import scala.concurrent.duration._

class SSERouter(implicit system: ActorSystem[_]) extends SuportRouter {

  val route = get {
    path("sse") {
      complete {
        Source
          .tick(1.seconds, 1.seconds, 1)
          .map(_ => LocalDateTime.now())
          .map(time => ServerSentEvent(time.toString))
      }
    }
  }
}
