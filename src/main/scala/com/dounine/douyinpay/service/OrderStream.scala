package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.headers.`User-Agent`
import akka.http.scaladsl.model.{
  HttpHeader,
  HttpMethods,
  HttpProtocol,
  HttpRequest,
  HttpResponse,
  StatusCode
}
import akka.stream.SystemMaterializer
import akka.stream.alpakka.slick.scaladsl.SlickSession
import akka.stream.scaladsl.{FileIO, Flow, Source}
import com.dounine.douyinpay.model.models.{
  AccountModel,
  OrderModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.service.PayPlatform
import com.dounine.douyinpay.store.{AccountTable, OrderTable}
import com.dounine.douyinpay.tools.akka.ConnectSettings
import com.dounine.douyinpay.tools.akka.db.DataSource
import com.dounine.douyinpay.tools.util.{
  DingDing,
  QrcodeUrlRandom,
  QrcodeUtil,
  Request
}
import slick.jdbc.JdbcBackend

import java.io.File
import java.nio.file.{Files, Paths}
import java.time.format.DateTimeFormatter
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContextExecutor, Future}

object OrderStream {

  def queryOrdersSuccess()(implicit
      system: ActorSystem[_]
  ): Source[
    Seq[OrderModel.DbInfo],
    NotUsed
  ] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._

    Source.future(
      db.run(
        OrderTable()
          .filter(_.pay === true)
          .result
      )
    )
  }

  def queryLatestOrder()(implicit
      system: ActorSystem[_]
  ): Flow[
    String,
    OrderModel.DbInfo,
    NotUsed
  ] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          OrderTable()
            .filter(i =>
              i.pay === false && i.openid === openid && i.createTime >= LocalDateTime
                .now()
                .minusDays(3)
            )
            .sortBy(_.createTime.desc)
            .take(1)
            .result
            .head
        )
      }
  }

  def qrcodeCreate()(implicit
      system: ActorSystem[_]
  ): Flow[
    OrderModel.DbInfo,
    (OrderModel.DbInfo, OrderModel.QrcodeResponse),
    NotUsed
  ] = {
    val config = system.settings.config
    val qrcodeUrl = QrcodeUrlRandom.random()
    val routerPrefix = config.getString("app.routerPrefix")
    val sharding = ClusterSharding(system)
    Flow[OrderModel.DbInfo]
      .mapAsync(1) { order: OrderModel.DbInfo =>
        order.platform match {
          case PayPlatform.douyin =>
            sharding
              .entityRefFor(
                DouyinAccountBehavior.typeKey,
                DouyinAccountBehavior.typeKey.name
              )
              .ask(
                DouyinAccountBehavior.Query(order.id)
              )(3.seconds)
              .flatMap {
                case DouyinAccountBehavior.QueryOk(cookie) =>
                  cookie match {
                    case Some(value) =>
                      Request
                        .post[OrderModel.QrcodeResponse](
                          qrcodeUrl + "/" + order.platform,
                          Map(
                            "order" -> order,
                            "timeout" -> 10 * 1000,
                            "cookie" -> cookie,
                            "callback" -> s"https://backup.61week.com/${routerPrefix}/order/update"
                          )
                        )
                        .map(order -> _)(system.executionContext)
                    case None =>
                      throw DouyinAccountFailException("当前充值系统用户已满，请联系公众号管理员处理")
                  }
                case DouyinAccountBehavior.QueryFail(q, msg) =>
                  throw DouyinAccountFailException(msg)
              }(system.executionContext)
          case PayPlatform.kuaishou =>
            Request
              .post[OrderModel.QrcodeResponse](
                qrcodeUrl + "/" + order.platform,
                Map(
                  "order" -> order,
                  "timeout" -> 10 * 1000,
                  "cookie" -> "",
                  "callback" -> s"https://backup.61week.com/${routerPrefix}/order/update"
                )
              )
              .map(order -> _)(system.executionContext)
          case _ => throw new RuntimeException("暂时不支持其它平台充值")
        }
      }
  }

  def downloadQrocdeFile()(implicit
      system: ActorSystem[_]
  ): Flow[
    (OrderModel.DbInfo, OrderModel.QrcodeResponse),
    (OrderModel.DbInfo, String),
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    Flow[(OrderModel.DbInfo, OrderModel.QrcodeResponse)]
      .mapAsync(1) { tp2: (OrderModel.DbInfo, OrderModel.QrcodeResponse) =>
        tp2._2.codeUrl match {
          case Some(url) =>
            tp2._1.platform match {
              case PayPlatform.douyin =>
                Future.successful(
                  tp2._1,
                  QrcodeUtil
                    .create2(
                      data = url,
                      markFile = Some(
                        OrderStream.getClass
                          .getResourceAsStream("/icon_wechatpay.jpeg")
                      )
                    )
                    .getAbsolutePath
                )
              case kuaishou =>
                Http(system)
                  .singleRequest(
                    HttpRequest(
                      uri = url,
                      headers = Seq(
                        `User-Agent`(
                          "Mozilla/5.0 (iPhone; CPU iPhone OS 11_0 like Mac OS X) AppleWebKit/604.1.38 (KHTML, like Gecko) Version/11.0 Mobile/15A372 Safari/604.1 wechatdevtools/1.05.2107221 MicroMessenger/8.0.5 Language/zh_CN webview/16314653301349493"
                        )
                      )
                    )
                  )
                  .map {
                    case HttpResponse(
                          code: StatusCode,
                          value: Seq[HttpHeader],
                          entity,
                          protocol: HttpProtocol
                        ) =>
                      value
                        .find(_.name().equalsIgnoreCase("Location")) match {
                        case Some(value) => Some(value.value())
                        case None        => None
                      }
                  }(system.executionContext)
                  .map {
                    case Some(url) =>
                      (
                        tp2._1,
                        QrcodeUtil
                          .create2(
                            data = url,
                            markFile = Some(
                              OrderStream.getClass
                                .getResourceAsStream("/icon_wechatpay.jpeg")
                            )
                          )
                          .getAbsolutePath
                      )
                    case None => throw new Exception("url获取失败、请联系客服")
                  }
              case huoshan =>
                throw new Exception("暂时不支持火山充值") //TODO future suport
            }
          case None =>
            Http(system)
              .singleRequest(
                request = HttpRequest(
                  method = HttpMethods.GET,
                  uri = tp2._2.qrcode.get
                ),
                settings = ConnectSettings.httpSettings(system)
              )
              .flatMap {
                case HttpResponse(code: StatusCode, value, entity, protocol) =>
                  val tmpFile = Files.createTempFile("qrcode", "png")
                  entity.dataBytes
                    .runWith(FileIO.toPath(tmpFile))
                    .map(_ => (tp2._1, tmpFile.toAbsolutePath.toString))
                case msg => {
                  Future.failed(new Exception(msg.toString()))
                }
              }
        }
      }
  }

  def notifyOrderCreateStatus()(implicit
      system: ActorSystem[_]
  ): Flow[
    (OrderModel.DbInfo, OrderModel.QrcodeResponse),
    (OrderModel.DbInfo, OrderModel.QrcodeResponse),
    NotUsed
  ] = {
    implicit val ec = system.executionContext
    val wechat = system.settings.config.getConfig("app.wechat")
    val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")
    val notify =
      (
          order: OrderModel.DbInfo,
          title: String,
          mType: DingDing.MessageType.MessageType,
          message: Option[String]
      ) => {
        val msg = message match {
          case Some(value) => s"\n - 错误信息: ${value}"
          case None        => ""
        }
        DingDing
          .sendMessageFuture(
            mType,
            data = DingDing.MessageData(
              markdown = DingDing.Markdown(
                title = "定单通知",
                text = s"""
                        |## ${title}
                        | - 公众号: ${wechat.getString(s"${order.appid}.name")}
                        | - 公众号ID: ${order.appid}
                        | - 当前渠道: ${order.ccode}
                        | - 微信昵称: ${order.nickName.getOrElse("")}
                        | - 充值平台：${order.platform}
                        | - 充值帐号: ${order.id}
                        | - 本次金额: ${order.money}
                        | - 今日充值次数: ${order.todayPayCount}
                        | - 今日充值金额: ${order.todayPayMoney}
                        | - 全部充值次数: ${order.payCount}
                        | - 全部充值金额: ${order.payMoney}${msg}
                        | - 耗时: ${java.time.Duration
                  .between(order.createTime, LocalDateTime.now())
                  .getSeconds}s
                        | - openid: ${order.openid}
                        | - 创建时间: ${order.createTime.format(
                  timeFormatter
                )}
                        | - 通知时间: ${LocalDateTime
                  .now()
                  .format(timeFormatter)}
                        |""".stripMargin
              )
            )
          )
          .map(_ => order)
      }
    Flow[(OrderModel.DbInfo, OrderModel.QrcodeResponse)]
      .mapAsync(1) { tp2 =>
        if (tp2._2.qrcode.isDefined || tp2._2.codeUrl.isDefined) {
          notify(tp2._1, "创建成功", DingDing.MessageType.order, None)
            .map(_ => tp2)
            .recover {
              case e => tp2
            }
        } else {
          notify(tp2._1, "创建失败", DingDing.MessageType.orderFail, tp2._2.message)
            .map(_ => tp2)
            .recover {
              case e => tp2
            }
        }
      }
  }

  def notifyOrderPayStatus()(implicit
      system: ActorSystem[_]
  ): Flow[OrderModel.DbInfo, OrderModel.DbInfo, NotUsed] = {
    implicit val ec = system.executionContext
    val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")
    val wechat = system.settings.config.getConfig("app.wechat")
    val notify = (
        typ: DingDing.MessageType.MessageType,
        wechatUser: WechatModel.WechatUserInfo,
        order: OrderModel.DbInfo,
        title: String
    ) => {
      DingDing
        .sendMessageFuture(
          typ,
          data = DingDing.MessageData(
            markdown = DingDing.Markdown(
              title = "定单通知",
              text = s"""
                        |## ${title}
                        | - 公众号: ${wechat.getString(s"${order.appid}.name")}
                        | - 是否关注：${wechatUser.subscribe == 1}
                        | - 当前渠道: ${order.ccode}
                        | - 微信昵称: ${order.nickName.getOrElse("未关注")}
                        | - 充值平台：${order.platform}
                        | - 充值帐号: ${order.id}
                        | - 本次金额: ${order.money}
                        | - 手续费：${order.fee / 100d}
                        | - 今日充值次数: ${order.todayPayCount}
                        | - 今日充值金额: ${order.todayPayMoney}
                        | - 全部充值次数: ${order.payCount}
                        | - 全部充值金额: ${order.payMoney}
                        | - 耗时: ${java.time.Duration
                .between(order.createTime, LocalDateTime.now())
                .getSeconds}s
                        | - openid: ${order.openid}
                        | - 创建时间: ${order.createTime.format(
                timeFormatter
              )}
                        | - 通知时间: ${LocalDateTime
                .now()
                .format(timeFormatter)}
                        |""".stripMargin
            )
          )
        )
        .map(_ => order)
    }
    Flow[OrderModel.DbInfo]
      .via(aggregation())
      .flatMapConcat(order => {
        Source
          .single((order.appid, order.openid))
          .via(WechatStream.userInfoQuery3())
          .map(_ -> order)
      })
      .mapAsync(1) {
        case (wechatUser: WechatModel.WechatUserInfo, order) =>
          if (order.pay) {
            notify(DingDing.MessageType.payed, wechatUser, order, "充值成功")
              .recover {
                case e => order
              }
          } else {
            notify(DingDing.MessageType.payerr, wechatUser, order, "充值失败")
              .recover {
                case e => order
              }
          }
      }
  }

  def queryOrder()(implicit
      system: ActorSystem[_]
  ): Flow[String, OrderModel.DbInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    Flow[String]
      .mapAsync(1) { orderId =>
        db.run(OrderTable().filter(_.orderId === orderId).result.head)
      }
  }

  def updateOrderStatus()(implicit
      system: ActorSystem[_]
  ): Flow[OrderModel.UpdateStatus, (OrderModel.UpdateStatus, Int), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    Flow[OrderModel.UpdateStatus]
      .mapAsync(1) { info =>
        db.run(
            OrderTable()
              .filter(_.orderId === info.order.orderId)
              .result
              .headOption
          )
          .flatMap {
            case Some(value) =>
              db.run(
                  OrderTable()
                    .filter(_.orderId === value.orderId)
                    .map(i => (i.pay, i.expire))
                    .update((info.pay, true))
                )
                .map(info -> _)
            case None => Future.successful((info, 0))
          }
      }
  }

  def add()(implicit
      system: ActorSystem[_]
  ): Flow[OrderModel.DbInfo, (OrderModel.DbInfo, Int), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer
    Flow[OrderModel.DbInfo]
      .mapAsync(1) { order: OrderModel.DbInfo =>
        db.run(OrderTable() += order).map(order -> _)
      }
  }

  def aggregation()(implicit
      system: ActorSystem[_]
  ): Flow[OrderModel.DbInfo, OrderModel.DbInfo, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer
    Flow[OrderModel.DbInfo]
      .mapAsync(1) { order: OrderModel.DbInfo =>
        db.run(
            OrderTable()
              .filter(i => i.openid === order.openid && i.pay === true)
              .result
          )
          .map { list =>
            order.copy(
              payCount = list.size,
              payMoney = list.map(_.money).sum,
              todayPayCount = list
                .filterNot(
                  _.createTime.isBefore(LocalDate.now().atStartOfDay())
                )
                .size,
              todayPayMoney = list
                .filterNot(
                  _.createTime.isBefore(LocalDate.now().atStartOfDay())
                )
                .map(_.money)
                .sum
            )
          }
      }
  }

  def queryPaySum()(implicit
      system: ActorSystem[_]
  ): Flow[String, Option[Int], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          OrderTable()
            .filter(i =>
              i.openid === openid && i.pay === true && i.createTime >= LocalDate
                .now()
                .atStartOfDay()
            )
            .map(_.money)
            .sum
            .result
        )
      }
  }

  def queryOpenidPaySum()(implicit
      system: ActorSystem[_]
  ): Flow[String, Option[Int], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          OrderTable()
            .filter(i => i.openid === openid && i.pay === true)
            .map(_.money)
            .sum
            .result
        )
      }
  }

  type SharePayedMoney = Int
  def queryOpenidSharedPay()(implicit
      system: ActorSystem[_]
  ): Flow[String, Option[SharePayedMoney], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          OrderTable()
            .filter(i =>
              i.ccode === openid && !(i.openid === openid) && i.pay === true && i.createTime >= LocalDate
                .now()
                .atStartOfDay()
            )
            .map(_.money)
            .sum
            .result
        )
      }
  }

  def queryTodayPaySum()(implicit
      system: ActorSystem[_]
  ): Source[(OrderModel.OrderReport, OrderModel.OrderReport), NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Source
      .future(
        db.run(
          OrderTable()
            .filter(i =>
              i.createTime >= LocalDate
                .now()
                .minusDays(1)
                .atStartOfDay()
                && i.createTime < LocalDate.now().atStartOfDay()
            )
            .result
            .map(result => {
              val pays = result.filter(_.pay)
              val noPays = result.filter(!_.pay)
              OrderModel.OrderReport(
                payCount = pays.size,
                payMoney = pays.map(_.money).sum,
                payPeople = pays.map(_.openid).distinct.size,
                noPayCount = noPays.size,
                noPayMoney = noPays.map(_.money).sum,
                noPayPeople = noPays.map(_.openid).distinct.size
              )
            })
        )
      )
      .zip(
        Source.future(
          db.run(
            OrderTable()
              .filter(i =>
                i.createTime >= LocalDate
                  .now()
                  .atStartOfDay()
              )
              .result
              .map(result => {
                val pays = result.filter(_.pay)
                val noPays = result.filter(!_.pay)
                OrderModel.OrderReport(
                  payCount = pays.size,
                  payMoney = pays.map(_.money).sum,
                  payPeople = pays.map(_.openid).distinct.size,
                  noPayCount = noPays.size,
                  noPayMoney = noPays.map(_.money).sum,
                  noPayPeople = noPays.map(_.openid).distinct.size
                )
              })
          )
        )
      )
  }

  def queryOpenidTodayPay()(implicit
      system: ActorSystem[_]
  ): Flow[String, Seq[OrderModel.DbInfo], NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[String]
      .mapAsync(1) { openid =>
        db.run(
          OrderTable()
            .filter(i =>
              i.pay === true && i.createTime >= LocalDate
                .now()
                .atStartOfDay() && i.openid === openid
            )
            .result
        )
      }
  }

  def queryIdAndIncrmentMoney()(implicit
      system: ActorSystem[_]
  ): Flow[(String, String), Int, NotUsed] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer

    Flow[(String, String)]
      .mapAsync(1) { result =>
        {
          db.run(
            AccountTable()
              .filter(_.openid === result._1)
              .result
              .headOption
              .map(result -> _)
          )
        }
      }
      .mapAsync(1) { tp2 =>
        val ((openid, id), result) = tp2
        result match {
          case Some(value) =>
            Future.successful(0)
          case None =>
            db.run(
              sql"""select openid,money
            from douyinpay_account
            where openid in (select distinct(openid) from douyinpay_order where id = ${id}) order by money desc limit 1
            """.stripMargin
                .as[(String, String)]
                .headOption
            )
              .flatMap(r => {
                if (r.isDefined) {
                  db.run(
                    AccountTable() += AccountModel.AccountInfo(
                      openid = openid,
                      money = r.get._2.toInt
                    )
                  )
                } else {
                  Future.successful(0)
                }
              })
        }
      }

  }

  def queryTodayNewUserPay()(implicit
      system: ActorSystem[_]
  ): Source[
    (List[OrderModel.NewUserPay], List[OrderModel.NewUserPay]),
    NotUsed
  ] = {
    val db: JdbcBackend.DatabaseDef = DataSource(system).source().db
    implicit val ec: ExecutionContextExecutor = system.executionContext
    implicit val slickSession: SlickSession =
      SlickSession.forDbAndProfile(db, slick.jdbc.MySQLProfile)
    import slickSession.profile.api._
    implicit val materializer = SystemMaterializer(system).materializer
    val today = LocalDate.now().toString
    val yestoday = LocalDate.now().minusDays(1).toString

    Source
      .future(
        db.run(
            sql"""
                select 
                       openid, 
                       sum(money) as payMoney, 
                       count(1) as payCount 
                from douyinpay_order
                where openid in 
                      (
                          select openid from douyinpay_openid where date_format(createTime, '%Y-%m-%d') = ${yestoday}
                        )
                    and pay = 1 and date_format(createTime, '%Y-%m-%d') = ${yestoday}
                    group by openid
               """.stripMargin.as[(String, Int, Int)]
          )
          .map(result => {
            result.toList.map(i => {
              OrderModel.NewUserPay(
                i._1,
                i._2,
                i._3
              )
            })
          })
      )
      .zip(
        Source.future(
          db.run(
              sql"""
                select 
                       openid, 
                       sum(money) as payMoney, 
                       count(1) as payCount 
                from douyinpay_order
                where openid in 
                      (
                          select openid from douyinpay_openid where date_format(createTime, '%Y-%m-%d') = ${today}
                        )
                    and pay = 1 and date_format(createTime, '%Y-%m-%d') = ${today}
                    group by openid
               """.stripMargin.as[(String, Int, Int)]
            )
            .map(result => {
              result.toList.map(i => {
                OrderModel.NewUserPay(
                  i._1,
                  i._2,
                  i._3
                )
              })
            })
        )
      )
  }

}
