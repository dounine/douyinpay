package com.dounine.douyinpay.router.routers.schema

import com.dounine.douyinpay.model.models.OpenidModel
import com.dounine.douyinpay.router.routers.SecureContext
import com.dounine.douyinpay.router.routers.errors.LockedException
import sangria.execution.{
  BeforeFieldResult,
  Middleware,
  MiddlewareBeforeField,
  MiddlewareQueryContext
}
import sangria.schema.Context

import scala.concurrent.duration._
import scala.concurrent.Await

object SecurityEnforcer
    extends Middleware[SecureContext]
    with MiddlewareBeforeField[SecureContext] {
  override type QueryVal = Unit
  override type FieldVal = Unit

  override def beforeQuery(
      context: MiddlewareQueryContext[SecureContext, _, _]
  ) = ()

  override def afterQuery(
      queryVal: QueryVal,
      context: MiddlewareQueryContext[SecureContext, _, _]
  ): Unit = ()

  override def beforeField(
      queryVal: QueryVal,
      mctx: MiddlewareQueryContext[SecureContext, _, _],
      ctx: Context[SecureContext, _]
  ): BeforeFieldResult[SecureContext, QueryVal] = {
    val requireAuth: Boolean = ctx.field.tags contains Authorised
    if (requireAuth) {
      val openInfo: OpenidModel.OpenidInfo = {
        Await.result(mctx.ctx.auth(), 3.seconds)
      }
      if (openInfo.locked) {
        throw LockedException("user locked -> " + openInfo.openid)
      }
      mctx.ctx.openid = Some(openInfo.openid)
      mctx.ctx.appid = Some(openInfo.appid)
    }
    continue
  }
}
