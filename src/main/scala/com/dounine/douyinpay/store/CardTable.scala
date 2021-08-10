package com.dounine.douyinpay.store

import com.dounine.douyinpay.model.models.{CardModel, UserModel}
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape

import java.time.LocalDateTime

class CardTable(tag: Tag)
    extends Table[CardModel.CardInfo](tag, _tableName = "douyinpay_card")
    with EnumMappers {

  override def * : ProvenShape[CardModel.CardInfo] =
    (
      id,
      money,
      openid,
      activeTime,
      createTime
    ).mapTo[CardModel.CardInfo]

  def id: Rep[String] = column[String]("id", O.PrimaryKey, O.Length(32))

  def money: Rep[BigDecimal] =
    column[BigDecimal]("money", O.SqlType("decimal(10, 2)"))

  def openid: Rep[Option[String]] =
    column[Option[String]]("openid", O.Length(100))

  def activeTime: Rep[LocalDateTime] =
    column[LocalDateTime](
      "activeTime",
      O.SqlType(timestampOnUpdate)
    )(
      localDateTime2timestamp
    )

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime](
      "createTime",
      O.SqlType(timestampOnCreate),
      O.Default(LocalDateTime.now())
    )(
      localDateTime2timestamp
    )

}
