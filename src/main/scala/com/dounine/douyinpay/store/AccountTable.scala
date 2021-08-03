package com.dounine.douyinpay.store

import com.dounine.douyinpay.model.models.{AccountModel, CardModel}
import slick.jdbc.MySQLProfile.api._
import slick.lifted.ProvenShape

class AccountTable(tag: Tag)
    extends Table[AccountModel.AccountInfo](
      tag,
      _tableName = "douyinpay_account"
    )
    with EnumMappers {

  override def * : ProvenShape[AccountModel.AccountInfo] =
    (
      openid,
      volumn
    ).mapTo[AccountModel.AccountInfo]

  def openid: Rep[String] = column[String]("openid", O.PrimaryKey, O.Length(32))

  def volumn: Rep[Int] = column[Int]("volumn", O.Length(32))

}
