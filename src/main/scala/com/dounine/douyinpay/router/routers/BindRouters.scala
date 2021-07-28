package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpRequest,
  HttpResponse
}
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.{
  ExceptionHandler,
  RejectionHandler,
  RequestContext,
  Route,
  RouteResult
}
import akka.pattern.AskTimeoutException
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future
import scala.concurrent.duration._

object BindRouters extends SuportRouter {

  val requestTimeout = ConfigFactory
    .load()
    .getDuration("akka.http.server.request-timeout")
    .toMillis

  def apply(
      system: ActorSystem[_],
      routers: Array[Route]
  ): RequestContext => Future[RouteResult] = {
    Route.seal(
      /**
        * all request default timeout
        * child request can again use withRequestTimeout, Level child > parent
        */
      withRequestTimeout(
        requestTimeout.millis,
        (_: HttpRequest) => timeoutResponse
      )(
        concat(
          routers: _*
        )
      )
    )
  }

}
