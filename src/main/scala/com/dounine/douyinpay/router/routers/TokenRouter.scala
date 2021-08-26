package com.dounine.douyinpay.router.routers

import akka.actor
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server.Route
import akka.stream._
import akka.stream.scaladsl.Source
import com.dounine.douyinpay.behaviors.engine.{
  AccessTokenBehavior,
  JSApiTicketBehavior
}
import org.slf4j.{Logger, LoggerFactory}

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration._
class TokenRouter()(implicit system: ActorSystem[_]) extends SuportRouter {

  val sharding = ClusterSharding(system)
  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[TokenRouter])
  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val actorSystem: actor.ActorSystem = materializer.system
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val wechat = system.settings.config.getConfig("app.wechat")

  val route: Route = pathPrefix("token") {
    concat(
      get {
        path("access" / Segment / Segment) {
          (appid, secret) =>
            val result = Source.future(
              sharding
                .entityRefFor(
                  AccessTokenBehavior.typeKey,
                  appid
                )
                .ask(
                  AccessTokenBehavior.GetToken()
                )(3.seconds)
                .map {
                  case AccessTokenBehavior.GetTokenOk(token) => {
                    if (wechat.getString(s"${appid}.secret") == secret) {
                      token
                    } else throw new Exception("secret invalid")
                  }
                  case AccessTokenBehavior.GetTokenFail(msg) =>
                    throw new Exception(msg)
                }
                .recover {
                  case e => failMsg(e.getMessage)
                }
                .map(okData)
            )
            complete(result)
        }
      },
      get {
        path("tick" / Segment / Segment) {
          (appid, secret) =>
            val result = Source.future(
              sharding
                .entityRefFor(
                  JSApiTicketBehavior.typeKey,
                  appid
                )
                .ask(
                  JSApiTicketBehavior.GetTicket()
                )(3.seconds)
                .map {
                  case JSApiTicketBehavior.GetTicketOk(token) => {
                    if (wechat.getString(s"${appid}.secret") == secret) {
                      token
                    } else throw new Exception("secret invalid")
                  }
                  case JSApiTicketBehavior.GetTicketFail(msg) =>
                    throw new Exception(msg)
                }
                .map(okData)
            )
            complete(result)
        }
      }
    )
  }

}
