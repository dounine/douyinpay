package com.dounine.douyinpay.store

import com.dounine.douyinpay.model.models.{BreakDownModel, OpenidModel}
import com.dounine.douyinpay.model.types.service.PayPlatform
import com.dounine.douyinpay.model.types.service.PayPlatform.PayPlatform
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape

import java.time.LocalDateTime

object BreakDownTable {
  def apply(): TableQuery[BreakDownTable] = TableQuery[BreakDownTable]
}
class BreakDownTable(tag: Tag)
    extends Table[BreakDownModel.BreakDownInfo](
      tag,
      _tableName = "douyinpay_breakdown"
    )
    with EnumMappers {

  override def * : ProvenShape[BreakDownModel.BreakDownInfo] =
    (
      id,
      orderId,
      account,
      platform,
      appid,
      openid,
      success,
      createTime
    ).mapTo[BreakDownModel.BreakDownInfo]

  def id: Rep[String] =
    column[String]("id", O.PrimaryKey, O.Length(32))

  def orderId: Rep[String] =
    column[String]("orderId", O.Length(32))

  def account: Rep[String] =
    column[String]("account")

  def platform: Rep[PayPlatform] =
    column[PayPlatform]("platform", O.Length(10))

  def appid: Rep[String] =
    column[String]("appid", O.Length(18))

  def openid: Rep[String] =
    column[String]("openid", O.Length(100))

  def success: Rep[Boolean] =
    column[Boolean]("success", O.Length(1))

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime](
      "createTime",
      O.SqlType(timestampOnCreate)
    )(
      localDateTime2timestamp
    )

  def queryNotSuccess =
    index(
      "douyinpay_breakdown_success_index",
      (createTime, success)
    )
}
