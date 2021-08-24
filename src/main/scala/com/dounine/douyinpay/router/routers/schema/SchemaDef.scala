package com.dounine.douyinpay.router.routers.schema

import akka.actor.typed.ActorSystem
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.Sink
import com.dounine.douyinpay.model.models.UserModel
import com.dounine.douyinpay.router.routers.{GraphStream, SecureContext}
import com.dounine.douyinpay.service.UserService
import com.dounine.douyinpay.tools.akka.cache.CacheSource
import com.dounine.douyinpay.tools.json.JsonParse
import sangria.execution.deferred.Fetcher
import sangria.schema._
import sangria.macros.derive._

import java.time.LocalDateTime
import sangria.streaming.ValidOutStreamType

import scala.concurrent.duration._
import scala.concurrent.Future
object SchemaDef extends JsonParse {

  implicit val UserTable = ObjectType(
    name = "User",
    description = "用户信息",
    fields = fields[Unit, UserModel.DbInfo](
      Field(
        name = "apiKey",
        fieldType = StringType,
        description = Some("钥匙"),
        resolve = _.value.apiKey
      ),
      Field(
        name = "balance",
        fieldType = BigDecimalType,
        description = Some("余额"),
        resolve = _.value.balance
      ),
      Field(
        name = "margin",
        fieldType = BigDecimalType,
        description = Some("冻结金额"),
        resolve = _.value.margin
      ),
      Field(
        name = "apiSecret",
        fieldType = StringType,
        description = Some("密钥"),
        resolve = _.value.apiSecret
      ),
      Field(
        name = "callback",
        fieldType = OptionType(StringType),
        description = Some("回调地扯"),
        resolve = _.value.callback
      ),
      Field(
        name = "createTime",
        fieldType = StringType,
        description = Some("创建时间"),
        resolve = _.value.createTime.toString
      )
    )
  )

  case class AddressInfo(
      ip: String,
      province: String,
      city: String
  )
  case class RequestInfo(
      url: String,
      scheme: String,
      headers: Map[String, String] = Map.empty,
      parameters: Map[String, String] = Map.empty,
      addressInfo: AddressInfo
  )

//  val query = ObjectType(
//    name = "Query",
//    description = "用户信息",
//    fields = fields[SecureContext, RequestInfo](
//      Field(
//        name = "login",
////        tags = Authorised :: Nil,
//        fieldType = OptionType(StringType),
//        description = Some("单个用户信息查询"),
//        resolve = c =>
//          GraphStream
//            .sourceSingle()(c.ctx.system)
//            .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
//      ),
//      Field(
//        name = "list",
//        tags = Authorised :: Nil,
//        fieldType = OptionType(StringType),
//        description = Some("单个用户信息查询"),
//        deprecationReason = Some("已过期"),
//        resolve = c =>
//          GraphStream
//            .sourceSingle()(c.ctx.system)
//            .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
//      )
//    )
//  )

  val query = ObjectType(
    name = "Query",
    description = "查询",
    fields = fields[SecureContext, RequestInfo](
      (OrderSchema.query ++ DouyinSchema.query): _*
    )
  )

  val mutation = ObjectType(
    name = "Mutation",
    description = "修改",
    fields = fields[SecureContext, RequestInfo](
      (WechatSchema.mutation ++ OrderSchema.mutation): _*
    )
  )

  val UserSchema = Schema(query = query, mutation = Some(mutation))

}
