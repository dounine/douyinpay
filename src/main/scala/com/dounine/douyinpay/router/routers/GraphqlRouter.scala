package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.dounine.douyinpay.router.routers.errors.{
  AuthException,
  LockedException
}
import com.dounine.douyinpay.router.routers.schema.{SchemaDef, SecurityEnforcer}
import com.dounine.douyinpay.tools.util.IpUtils
import io.circe.Json
import org.slf4j.LoggerFactory
import sangria.execution.{
  ErrorWithResolver,
  ExceptionHandler,
  ExecutionError,
  Executor,
  HandledException,
  QueryAnalysisError,
  ResultResolver
}
import sangria.marshalling.circe._
import sangria.slowlog.SlowLog

import scala.concurrent.duration._
import scala.util.{Failure, Success}
import sangria.http.akka.circe.CirceHttpSupport
import sangria.marshalling.ResultMarshaller

case class GraphException(message: String, eh: Executor.ExceptionHandler)
    extends ExecutionError(message, eh)
    with QueryAnalysisError
class GraphqlRouter()(implicit system: ActorSystem[_])
    extends CirceHttpSupport {
  private val logger = LoggerFactory.getLogger(classOf[GraphqlRouter])
  implicit val ec = system.executionContext

  val route: Route =
    cors() {
      optionalHeaderValueByName("X-Apollo-Tracing") { tracing =>
        path("graphql") {
          graphQLPlayground ~
            parameterMap { parameters: Map[String, String] =>
              extractRequest { request =>
                prepareGraphQLRequest {
                  case Success(req) =>
                    val headers = request.headers
                      .map(h => h.name() -> h.value())
                      .toMap

                    val scheme: String = headers
                      .getOrElse("X-Scheme", request.uri.scheme)
                    val slowLog = SlowLog(logger, threshold = 1.seconds)
                    val middleware = tracing match {
                      case Some(value) =>
                        slowLog :: SlowLog.extension :: SecurityEnforcer :: Nil
                      case None => SecurityEnforcer :: Nil
                    }
                    val exceptionHandler = ExceptionHandler {
                      case (m, e: AuthException) =>
                        HandledException(e.getMessage)
                      case (m, e: LockedException) =>
                        HandledException("Internal server error")
                      case (m, e: IllegalStateException) =>
                        HandledException(e.getMessage)
                      case (m, e: Exception) =>
                        HandledException(e.getMessage)
                    }

                    val ip: String = Seq(
                      "X-Forwarded-For",
                      "X-Real-Ip",
                      "Remote-Address"
                    ).map(
                        headers.get
                      )
                      .find(_.isDefined)
                      .flatMap(_.map(i => i.split(",").head))
                      .getOrElse("127.0.0.1")

                    val (province, city) =
                      IpUtils.convertIpToProvinceCity(ip)

                    val requestInfo = SchemaDef.RequestInfo(
                      url = request.uri.toString(),
                      origin = headers.getOrElse("Origin", ""),
                      referer = headers.getOrElse("Referer", ""),
                      scheme = scheme,
                      parameters = parameters,
                      headers = headers,
                      addressInfo = SchemaDef.AddressInfo(
                        ip = ip,
                        province = province,
                        city = city
                      )
                    )
                    val graphQLResponse = Executor
                      .execute(
                        schema = SchemaDef.UserSchema,
                        queryAst = req.query,
                        userContext = new SecureContext(system, requestInfo),
                        root = requestInfo,
                        variables = req.variables,
                        operationName = req.operationName,
                        middleware = middleware,
                        exceptionHandler = exceptionHandler
                      )
                      .map(OK -> (_: Json))
                      .recover {
                        case error: QueryAnalysisError =>
                          error.printStackTrace()
                          BadRequest -> error.resolveError
                        case error: ErrorWithResolver =>
                          error.printStackTrace()
                          InternalServerError -> error.resolveError
                      }
                    complete(graphQLResponse)
                  case Failure(preparationError) =>
                    complete(BadRequest, formatError(preparationError))
                }
              }
            }
        }
      }
    }
}
