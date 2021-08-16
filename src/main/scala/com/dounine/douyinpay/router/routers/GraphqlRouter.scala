package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import io.circe.Json
import org.slf4j.LoggerFactory
import sangria.execution.{
  ErrorWithResolver,
  ExceptionHandler,
  ExecutionError,
  Executor,
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
                    val slowLog = SlowLog(logger, threshold = 1.seconds)
                    val middleware = tracing match {
                      case Some(value) => slowLog :: SlowLog.extension :: Nil
                      case None        => Nil
                    }
                    val graphQLResponse = Executor
                      .execute(
                        schema = SchemaDef.UserSchema,
                        queryAst = req.query,
                        userContext = system,
                        root = SchemaDef.RequestInfo(
                          url = request.uri.toString(),
                          parameters = parameters,
                          headers = request.headers
                            .map(h => h.name() -> h.value())
                            .toMap,
                          ip = Seq(
                            "X-Forwarded-For",
                            "X-Real-Ip",
                            "Remote-Address"
                          ).map(
                              request.headers
                                .map(h => h.name() -> h.value())
                                .toMap
                                .get
                            )
                            .find(_.isDefined)
                            .flatMap(_.map(i => i.split(",").head))
                            .getOrElse("localhost")
                        ),
                        variables = req.variables,
                        operationName = req.operationName,
                        middleware = middleware
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
