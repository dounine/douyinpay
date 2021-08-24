package com.dounine.douyinpay.service

import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.{
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse,
  MediaTypes
}
import akka.stream.alpakka.slick.scaladsl.SlickSession
import akka.stream.{RestartSettings, SystemMaterializer}
import akka.stream.scaladsl.{RestartSource, Sink, Source}
import akka.util.ByteString
import com.dounine.douyinpay.model.models.{OrderModel, UserModel}
import com.dounine.douyinpay.model.types.service.MechinePayStatus.MechinePayStatus
import com.dounine.douyinpay.model.types.service.PayPlatform.PayPlatform
import com.dounine.douyinpay.model.types.service.PayStatus
import com.dounine.douyinpay.model.types.service.PayStatus.PayStatus
import com.dounine.douyinpay.store.{EnumMappers, OrderTable, UserTable}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.util.{MD5Util, Request}
import org.slf4j.LoggerFactory
import slick.jdbc.MySQLProfile.api._
import slick.lifted.TableQuery

import scala.concurrent.Future
import scala.concurrent.duration._
class OrderService(implicit system: ActorSystem[_]) extends EnumMappers {
  private val db = DataSource(system).source().db
  private val dict: TableQuery[OrderTable] =
    TableQuery[OrderTable]

  implicit val ec = system.executionContext
  implicit val materializer = SystemMaterializer(system).materializer
  implicit val slickSession =
    SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)

  val insertAndGetId =
    dict returning dict.map(_.orderId) into ((item, id) => id)

  def queryIdInfo(id: String): Future[(Int, Int)] = {
    db.run(dict.filter(db => db.id === id && db.pay === true).result)
      .map(list => {
        (list.size, list.foldLeft(0)((s, n) => s + n.money))
      })
  }

  def updateOrderStatus(orderId: String, pay: Boolean): Future[Int] = {
    db.run(dict.filter(_.orderId === orderId).result.headOption)
      .flatMap {
        case Some(value) =>
          db.run(
            dict.filter(_.orderId === value.orderId).map(_.pay).update(pay)
          )
        case None => Future.successful(0)
      }
  }

  def queryOrderStatus(
      orderId: String
  ): Future[String] = {
    db.run(dict.filter(_.orderId === orderId).result.headOption)
      .map(item => {
        if (item.isDefined) {
          if (item.get.pay) {
            "pay"
          } else if (item.get.expire) {
            "expire"
          } else {
            "nopay"
          }
        } else throw new Exception("定单不存在")
      })
  }

  def add(info: OrderModel.DbInfo): Future[Int] = db.run(dict += info)

  def update(info: OrderModel.DbInfo): Future[Int] =
    db.run(
      dict.insertOrUpdate(info)
    )

  def updateAll(info: OrderModel.DbInfo): Future[Int] =
    db.run(
      dict
        .filter(_.orderId === info.orderId)
        .map(item =>
          (
            item.pay,
            item.expire,
            item.payCount,
            item.payMoney
          )
        )
        .update((info.pay, info.expire, info.payCount, info.payMoney))
    )

  def userInfo(
      platform: PayPlatform,
      userId: String
  ): Future[Option[OrderModel.UserInfo]] = {
    Request
      .get[OrderModel.DouYinSearchResponse](
        s"https://webcast.amemv.com/webcast/user/open_info/?search_ids=${userId}&aid=1128&source=1a0deeb4c56147d0f844d473b325a28b&fp=verify_khq5h2bx_oY8iEaW1_b0Yt_4Hvt_9PRa_3U70XFUYPgzI&t=${System
          .currentTimeMillis()}"
      )
      .map(item => {
        if (item.data.open_info.nonEmpty) {
          val data: OrderModel.DouYinSearchOpenInfo =
            item.data.open_info.head
          Option(
            OrderModel.UserInfo(
              nickName = data.nick_name,
              id = data.search_id,
              avatar = data.avatar_thumb.url_list.head
            )
          )
        } else {
          Option.empty
        }
      })
  }

}
