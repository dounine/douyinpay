package com.dounine.douyinpay.store

import com.dounine.douyinpay.model.models.OrderModel
import com.dounine.douyinpay.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.douyinpay.model.types.service.{PayPlatform, PayStatus}
import com.dounine.douyinpay.model.types.service.PayPlatform.PayPlatform
import com.dounine.douyinpay.model.types.service.PayStatus.PayStatus
import slick.jdbc.MySQLProfile.api._
import slick.lifted.{PrimaryKey, ProvenShape}

import java.time.LocalDateTime

class OrderTable(tag: Tag)
    extends Table[OrderModel.DbInfo](tag, _tableName = "douyinpay_order")
    with EnumMappers {

  override def * : ProvenShape[OrderModel.DbInfo] =
    (
      appid,
      openid,
      nickName,
      pay,
      expire,
      orderId,
      id,
      money,
      volumn,
      fee,
      platform,
      createTime,
      payCount,
      payMoney,
      todayPayCount,
      todayPayMoney
    ).mapTo[OrderModel.DbInfo]

  def appid: Rep[String] = column[String]("appid", O.Length(18))

  def openid: Rep[String] = column[String]("openid", O.Length(50))

  def nickName: Rep[Option[String]] =
    column[Option[String]]("nickName", O.Length(30))

  def orderId: Rep[String] =
    column[String]("orderId", O.Length(64))

  def id: Rep[String] = column[String]("id", O.Length(50))

  def money: Rep[Int] = column[Int]("money", O.Length(11))

  def payCount: Rep[Int] = column[Int]("payCount", O.Length(11))

  def payMoney: Rep[Int] = column[Int]("payMoney", O.Length(11))

  def todayPayCount: Rep[Int] = column[Int]("todayPayCount", O.Length(11))

  def todayPayMoney: Rep[Int] = column[Int]("todayPayMoney", O.Length(11))

  def volumn: Rep[Int] = column[Int]("volumn", O.Length(11))

  def fee: Rep[BigDecimal] =
    column[BigDecimal]("fee", O.SqlType("decimal(10, 2)"))

  def platform: Rep[PayPlatform] =
    column[PayPlatform]("platform", O.Length(PayPlatform.dbLength))

  def pay: Rep[Boolean] = column[Boolean]("pay", O.Length(1))

  def expire: Rep[Boolean] = column[Boolean]("expire", O.Length(1))

  def idx = index("douyinpay_order_orderId_uindex", orderId, unique = true)

  def queryPayDay =
    index(
      "douyinpay_order_createTime_pay_index",
      (createTime, pay)
    )

  def payIndex =
    index(
      "douyinpay_order_pay_index",
      pay
    )

  def createTime: Rep[LocalDateTime] =
    column[LocalDateTime]("createTime", O.SqlType(timestampOnCreate))(
      localDateTime2timestamp
    )

}
