package com.dounine.douyinpay.store

import com.dounine.douyinpay.model.models.{AccountModel, CardModel}
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape

object AccountTable {
  def apply(): TableQuery[AccountTable] = TableQuery[AccountTable]
}
class AccountTable(tag: Tag)
    extends Table[AccountModel.AccountInfo](
      tag,
      _tableName = "douyinpay_account"
    )
    with EnumMappers {

  override def * : ProvenShape[AccountModel.AccountInfo] =
    (
      openid,
      money
    ).mapTo[AccountModel.AccountInfo]

  def openid: Rep[String] = column[String]("openid", O.PrimaryKey, O.Length(32))

  def money: Rep[BigDecimal] =
    column[BigDecimal]("money", O.SqlType("decimal(10, 2)"))

}
