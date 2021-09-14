package com.dounine.douyinpay.store

import com.dounine.douyinpay.model.models.{PayModel, UserModel}
import com.dounine.douyinpay.model.types.service.PayStatus
import com.dounine.douyinpay.model.types.service.PayStatus.PayStatus
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape

import java.time.LocalDateTime

object PayTable {
  def apply(): TableQuery[PayTable] = TableQuery[PayTable]
}
class PayTable(tag: Tag)
    extends Table[PayModel.PayInfo](tag, _tableName = "douyinpay_pay")
    with EnumMappers {

  override def * : ProvenShape[PayModel.PayInfo] =
    (
      id,
      money,
      openid,
      pay,
      payTime,
      createTime
    ).mapTo[PayModel.PayInfo]

  def id: Rep[String] = column[String]("id", O.PrimaryKey, O.Length(32))

  def money: Rep[Int] =
    column[Int]("money")

  def openid: Rep[String] =
    column[String]("openid", O.Length(100))

  def pay: Rep[PayStatus] =
    column[PayStatus]("pay", O.Length(PayStatus.dbLength))

  def payTime: Rep[LocalDateTime] =
    column[LocalDateTime](
      "payTime",
      O.SqlType(timestampOnUpdate)
    )(
      localDateTime2timestamp
    )

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime](
      "createTime",
      O.SqlType(timestampOnCreate)
    )(
      localDateTime2timestamp
    )

}
