package com.dounine.douyinpay.store

import com.dounine.douyinpay.model.models.{CardModel, OpenidModel}
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape

import java.time.LocalDateTime

class OpenidTable(tag: Tag)
    extends Table[OpenidModel.OpenidInfo](tag, _tableName = "douyinpay_openid")
    with EnumMappers {

  override def * : ProvenShape[OpenidModel.OpenidInfo] =
    (
      openid,
      ccode,
      ip,
      locked,
      createTime
    ).mapTo[OpenidModel.OpenidInfo]

  def openid: Rep[String] =
    column[String]("openid", O.PrimaryKey, O.Length(100))

  def ccode: Rep[String] =
    column[String]("ccode", O.Length(100))

  def ip: Rep[String] =
    column[String]("ip", O.Length(15))

  def locked: Rep[Boolean] =
    column[Boolean]("locked", O.Length(1))

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime](
      "createTime",
      O.SqlType(timestampOnCreate)
    )(
      localDateTime2timestamp
    )

}
