package com.dounine.douyinpay.store

import com.dounine.douyinpay.model.models.UserModel
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape

import java.time.LocalDateTime

class UserTable(tag: Tag)
    extends Table[UserModel.DbInfo](tag, _tableName = "douyinpay_user")
    with EnumMappers {

  override def * : ProvenShape[UserModel.DbInfo] =
    (
      apiKey,
      apiSecret,
      balance,
      margin,
      callback,
      createTime
    ).mapTo[UserModel.DbInfo]

  def apiKey: Rep[String] = column[String]("apiKey", O.PrimaryKey, O.Length(32))

  def apiSecret: Rep[String] = column[String]("apiSecret", O.Length(32))

  def callback: Rep[Option[String]] =
    column[Option[String]]("callback", O.Length(100))

  def balance: Rep[BigDecimal] = column[BigDecimal]("balance", O.Length(11))

  def margin: Rep[BigDecimal] = column[BigDecimal]("margin", O.Length(11))

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime]("createTime", O.SqlType(timestampOnCreate))(
      localDateTime2timestamp
    )

}
