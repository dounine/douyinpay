package com.dounine.douyinpay.router.routers.schema

import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{
  AccountModel,
  OpenidModel,
  OrderModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.service.{LogEventKey, PayPlatform}
import com.dounine.douyinpay.router.routers.SecureContext
import com.dounine.douyinpay.router.routers.errors.{
  DouyinAccountFailException,
  InvalidException,
  LockedException,
  PayManyException
}
import com.dounine.douyinpay.router.routers.schema.SchemaDef.RequestInfo
import com.dounine.douyinpay.service.OrderStream.SharePayedMoney
import com.dounine.douyinpay.service.{
  AccountStream,
  OpenidStream,
  OrderService,
  OrderStream,
  WechatStream
}
import com.dounine.douyinpay.tools.akka.cache.CacheSource
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util
import com.dounine.douyinpay.tools.util.{
  MD5Util,
  OpenidPaySuccess,
  ServiceSingleton,
  UUIDUtil
}
import org.slf4j.LoggerFactory
import sangria.macros.derive.{
  DocumentField,
  Interfaces,
  ObjectTypeDescription,
  ObjectTypeName,
  ReplaceField,
  deriveObjectType
}
import sangria.schema._

import java.text.DecimalFormat
import java.time.{LocalDate, LocalDateTime}
import scala.concurrent.Future
import scala.concurrent.duration._
object OrderSchema extends JsonParse {

  private val logger = LoggerFactory.getLogger(OrderSchema.getClass)

  implicit val MoneyMenuItem =
    deriveObjectType[Unit, OrderModel.MoneyMenuItem](
      ObjectTypeName("MoneyMenuItem"),
      ObjectTypeDescription("金额"),
      DocumentField("money", "金额"),
      DocumentField("volumn", "币"),
      DocumentField("enought", "是否足够抵扣"),
      DocumentField("commonEnought", "常规用户是否足够充值"),
      DocumentField("vipEnought", "vip用户是否足够充值")
    )

  val MoneyMenuResponse = ObjectType(
    name = "MoneyMenuResponse",
    description = "金额信息",
    fields = fields[Unit, OrderModel.MoneyMenuResponse](
      Field(
        name = "bu",
        fieldType = OptionType(StringType),
        description = Some("bu"),
        resolve = _.value.bu
      ),
      Field(
        name = "list",
        fieldType = ListType(MoneyMenuItem),
        description = Some("列表"),
        resolve = _.value.list
      ),
      Field(
        name = "balance",
        fieldType = OptionType(StringType),
        description = Some("用户余额"),
        resolve = _.value.balance
      ),
      Field(
        name = "commonRemain",
        fieldType = IntType,
        description = Some("常规用户可用额度"),
        resolve = _.value.commonRemain
      ),
      Field(
        name = "targetUser",
        fieldType = BooleanType,
        description = Some("是否是目标用户"),
        resolve = _.value.targetUser
      ),
      Field(
        name = "vipRemain",
        fieldType = OptionType(StringType),
        description = Some("VIP用户可用额度"),
        resolve = _.value.vipRemain
      )
    )
  )

  val moneyFormat = new DecimalFormat("###,###.00")
  val volumnFormat = new DecimalFormat("###,###")

  val newUserMoneys = List(
    1, 6, 30, 98, 298, 518, 1598, 3000
  )
  val vipUserMoneys = List(
    6, 30, 98, 298, 518, 1598, 3000, 5000
  )

  val moneyMenu = Field[
    SecureContext,
    RequestInfo,
    OrderModel.MoneyMenuResponse,
    OrderModel.MoneyMenuResponse
  ](
    name = "moneyMenu",
    fieldType = MoneyMenuResponse,
    tags = Authorised :: Nil,
    description = Some("获取充值金额列表"),
    arguments = Arguments.platform :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val system = c.ctx.system
      val openid = c.ctx.openid.get
      val backUrl = "https://backup.61week.com/api"
      val platform = c.arg(Arguments.platform)
      Source
        .single(c.ctx.openid.get)
        .via(AccountStream.query()(c.ctx.system))
        .zip(
          Source
            .single(c.ctx.openid.get)
            .via(OrderStream.queryOpenidTodayPay()(c.ctx.system))
        )
        .zip(
          Source
            .single(c.ctx.openid.get)
            .via(OrderStream.queryOpenidSharedPay())
        )
        .zipWith(
          Source
            .single(c.ctx.openid.get)
            .via(
              OpenidStream.query()
            )
            .zip(
              Source
                .single(c.ctx.openid.get)
                .via(OrderStream.queryOpenidPaySum())
            )
        ) { (pre, next) =>
          (
            pre._1._1,
            pre._1._2,
            pre._2,
            next._1,
            next._2
          )
        }
        .map {
          case (
                vipUser: Option[AccountModel.AccountInfo],
                todayOrders: Seq[OrderModel.DbInfo],
                sharePayed: Option[SharePayedMoney],
                wechatInfo: Option[OpenidModel.OpenidInfo],
                userPaySum: Option[Int]
              ) => {
            val commonRemain: Int =
              (100 + sharePayed.getOrElse(0) / 2) - todayOrders.map(_.money).sum
            val todayRemain: Double =
              if (commonRemain < 0) 0d else commonRemain * 0.02

            val coinBiLI = platform match {
              case PayPlatform.douyin   => 10
              case PayPlatform.kuaishou => 10
              case PayPlatform.huoshan  => 10
              case PayPlatform.douyu    => 1
              case PayPlatform.huya     => 1
            }
            val admins = c.ctx.system.settings.config.getStringList("app.admins")
            vipUser match {
              case Some(vip) =>
                if (admins.contains(openid)) {
                  OrderModel.MoneyMenuResponse(
                    bu = userPaySum.flatMap(i => {
                      if (i > 100) {
                        Some(backUrl)
                      } else None
                    }),
                    list = newUserMoneys
                      .map(money =>
                        (
                          moneyFormat.format(money),
                          volumnFormat.format(money * coinBiLI)
                        )
                      )
                      .map(tp2 => {
                        OrderModel.MoneyMenuItem(
                          money = tp2._1,
                          volumn = tp2._2,
                          enought = Some(true),
                          commonEnought = true
                        )
                      }),
                    targetUser = false,
                    commonRemain = 100
                  )
                } else {
                  val list = (if (admins.contains(openid))
                                List(1) ++ vipUserMoneys
                              else vipUserMoneys)
                    .map(money =>
                      (
                        moneyFormat.format(money),
                        volumnFormat.format(money * coinBiLI)
                      )
                    )
                    .map(tp2 => {
                      val money = moneyFormat.parse(tp2._1).intValue()
                      OrderModel.MoneyMenuItem(
                        money = tp2._1,
                        volumn = tp2._2,
                        enought = Some(
                          ((todayRemain * 100 + vip.money) - (money * 100 * 0.02)) >= 0
                        ),
                        commonEnought = commonRemain - money >= 0,
                        vipEnought = Some(
                          vip.money - money * 100 * 0.02 >= 0
                        )
                      )
                    })
                  OrderModel.MoneyMenuResponse(
                    bu = userPaySum.flatMap(i => {
                      if (i > 100) {
                        Some(backUrl)
                      } else None
                    }),
                    list = list,
                    targetUser = !admins.contains(openid),
                    commonRemain = commonRemain,
                    vipRemain = Some((vip.money / 100.0).formatted("%.2f")),
                    balance = Some(
                      (todayRemain + (vip.money / 100.0)).formatted("%.2f")
                    )
                  )
                }
              case None =>
                val payInfo = OpenidPaySuccess
                  .query(openid)
                if (
                  LocalDate
                    .now()
                    .atStartOfDay()
                    .isAfter(
                      wechatInfo.get.createTime
                        .plusDays(1)
                    ) && payInfo.count > 2 && payInfo.money > 100
                ) {
                  val list = vipUserMoneys
                    .map(money =>
                      (
                        moneyFormat.format(money),
                        volumnFormat.format(money * coinBiLI)
                      )
                    )
                    .map(tp2 => {
                      val money = moneyFormat.parse(tp2._1).intValue()
                      OrderModel.MoneyMenuItem(
                        money = tp2._1,
                        volumn = tp2._2,
                        enought = Some(
                          (todayRemain - (money * 0.02)) >= 0
                        ),
                        commonEnought = commonRemain - money >= 0
                      )
                    })
                  OrderModel.MoneyMenuResponse(
                    bu = userPaySum.flatMap(i => {
                      if (i > 100) {
                        Some(backUrl)
                      } else None
                    }),
                    list = list,
                    targetUser = true,
                    commonRemain = commonRemain,
                    balance = Some(todayRemain.formatted("%.2f"))
                  )
                } else {
                  OrderModel.MoneyMenuResponse(
                    bu = userPaySum.flatMap(i => {
                      if (i > 100) {
                        Some(backUrl)
                      } else None
                    }),
                    list = newUserMoneys
                      .map(money =>
                        (
                          moneyFormat.format(money),
                          volumnFormat.format(money * coinBiLI)
                        )
                      )
                      .map(tp2 => {
                        OrderModel.MoneyMenuItem(
                          money = tp2._1,
                          volumn = tp2._2,
                          enought = Some(true),
                          commonEnought = true
                        )
                      }),
                    targetUser = false,
                    commonRemain = 100
                  )
                }
            }
          }
        }
        .map(result => {
          logger.info(
            Map(
              "time" -> System
                .currentTimeMillis(),
              "data" -> Map(
                "event" -> LogEventKey.payMoneyMenu,
                "data" -> result,
                "openid" -> openid,
                "ip" -> c.value.addressInfo.ip,
                "province" -> c.value.addressInfo.province,
                "city" -> c.value.addressInfo.city
              )
            ).toJson
          )
          result
        })
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )

  val orderStatus = Field[
    SecureContext,
    RequestInfo,
    String,
    String
  ](
    name = "orderStatus",
    fieldType = StringType,
    tags = Authorised :: Nil,
    description = Some("定单支付状态"),
    arguments = Arguments.orderId :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) =>
      ServiceSingleton
        .get(classOf[OrderService])
        .queryOrderStatus(c.arg(Arguments.orderId))
  )

  val OrderCreateResponse =
    deriveObjectType[Unit, OrderModel.OrderCreateResponse](
      ObjectTypeName("OrderCreateResponse"),
      ObjectTypeDescription("定单创建响应"),
      DocumentField("queryUrl", "定单状态查询地扯"),
      DocumentField("qrcodeUrl", "支付二维码地扯")
    )

  val orderCreate = Field[
    SecureContext,
    RequestInfo,
    OrderModel.OrderCreateResponse,
    OrderModel.OrderCreateResponse
  ](
    name = "orderCreate",
    fieldType = OrderCreateResponse,
    tags = Authorised :: Nil,
    description = Some("创建支付定单"),
    arguments =
      Arguments.id :: Arguments.money :: Arguments.platform :: Arguments.ccode :: Arguments.domain :: Arguments.sign :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val system = c.ctx.system
      val money = moneyFormat.parse(c.arg(Arguments.money)).intValue()
      val openid = c.ctx.openid.get
      val domain = c.arg(Arguments.domain)
      Source
        .single(
          OrderModel.Recharge(
            id = c.arg(Arguments.id),
            money = c.arg(Arguments.money),
            platform = c.arg(Arguments.platform),
            ccode = c.arg(Arguments.ccode),
            domain = c.arg(Arguments.domain),
            sign = c.arg(Arguments.sign)
          )
        )
        .map(_ -> c.ctx.openid.get)
        .mapAsync(1) { tp2 =>
          CacheSource(c.ctx.system)
            .cache()
            .get[String](
              key = "qrcodeCreateFail_" + tp2._1.platform + openid
            )
            .map {
              case Some(value) =>
                throw DouyinAccountFailException(value)
              case None =>
                tp2
            }(c.ctx.system.executionContext)
        }
        .mapAsync(1) {
          tp2 =>
            CacheSource(c.ctx.system)
              .cache()
              .get[LocalDateTime]("createOrder_" + tp2._1.platform + openid)
              .map {
                case Some(time) => {
                  val nextSeconds = java.time.Duration
                    .between(time, LocalDateTime.now())
                    .getSeconds
                  if (nextSeconds <= 60) {
                    logger.error(
                      Map(
                        "time" -> System
                          .currentTimeMillis(),
                        "data" -> Map(
                          "event" -> LogEventKey.orderPayManay,
                          "recharge" -> tp2._1,
                          "openid" -> openid,
                          "ip" -> c.value.addressInfo.ip,
                          "province" -> c.value.addressInfo.province,
                          "city" -> c.value.addressInfo.city
                        )
                      ).toJson
                    )
                    throw PayManyException(
                      s"您上一笔定单未支付、请于 ${60 - nextSeconds} 秒后再操作"
                    )
                  }
                  tp2
                }
                case None => tp2
              }(c.ctx.system.executionContext)
        }
        .map(i => {
          if (
            MD5Util.md5(
              Array(
                i._1.id,
                i._1.platform.toString,
                i._1.money,
                i._1.ccode,
                i._1.domain,
                openid
              ).sorted.mkString("")
            ) != i._1.sign
          ) {
            logger.error(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.orderCreateSignError,
                  "openid" -> openid,
                  "payPlatform" -> i._1.platform,
                  "payAccount" -> i._1.id,
                  "payMoney" -> i._1.money,
                  "payCcode" -> i._1.ccode,
                  "sign" -> i._1.sign,
                  "domain" -> i._1.domain,
                  "ip" -> c.value.addressInfo.ip,
                  "province" -> c.value.addressInfo.province,
                  "city" -> c.value.addressInfo.city
                )
              ).toJson
            )
            throw new Exception("创建定单验证不通过")
          }
          i
        })
        .via(WechatStream.userInfoQuery(c.ctx.appid.get)(c.ctx.system))
        .map(tp2 => {
          val data = tp2._1
          val orderId = UUIDUtil.uuid()
          val userInfo = tp2._2
          val order = OrderModel.DbInfo(
            appid = c.ctx.appid.get,
            ccode = data.ccode,
            orderId = orderId,
            nickName = userInfo.nickname,
            pay = false,
            expire = false,
            openid = openid,
            id = data.id,
            money = moneyFormat.parse(data.money).intValue(),
            volumn = volumnFormat.parse(data.money).intValue() * 10,
            fee = 0,
            platform = data.platform,
            createTime = LocalDateTime.now()
          )
          logger.info(
            Map(
              "time" -> System.currentTimeMillis(),
              "data" -> Map(
                "event" -> LogEventKey.orderCreateRequest,
                "order" -> order,
                "ip" -> c.value.addressInfo.ip,
                "province" -> c.value.addressInfo.province,
                "city" -> c.value.addressInfo.city
              )
            ).toJson
          )
          order
        })
        .flatMapConcat(i => {
          val payInfo = OpenidPaySuccess
            .query(openid)
          val admins = c.ctx.system.settings.config.getStringList("app.admins")
          if (admins.contains(openid)) {
            Source.single(i)
          } else if (payInfo.count > 2 && payInfo.money > 100) {
            Source
              .single(c.ctx.openid.get)
              .via(AccountStream.query()(c.ctx.system))
              .zip(
                Source
                  .single(c.ctx.openid.get)
                  .via(OrderStream.queryOpenidTodayPay()(c.ctx.system))
              )
              .zip(
                Source
                  .single(c.ctx.openid.get)
                  .via(OrderStream.queryOpenidSharedPay())
              )
              .zipWith(
                Source
                  .single(c.ctx.openid.get)
                  .via(
                    OpenidStream.query()
                  )
              ) { (pre, next) =>
                (
                  pre._1._1,
                  pre._1._2,
                  pre._2,
                  next
                )
              }
              .map {
                case (
                      vipUser: Option[AccountModel.AccountInfo],
                      todayOrders: Seq[OrderModel.DbInfo],
                      sharePayed: Option[SharePayedMoney],
                      wechatUser: Option[OpenidModel.OpenidInfo]
                    ) =>
                  val commonRemain: Int =
                    (100 + sharePayed
                      .getOrElse(0) / 2) - todayOrders.map(_.money).sum
                  val todayRemain: Double =
                    if (commonRemain < 0) 0d else commonRemain * 0.02
                  if (
                    LocalDate
                      .now()
                      .atStartOfDay()
                      .isAfter(
                        wechatUser.get.createTime
                          .plusDays(1)
                      )
                  ) {
                    if (todayOrders.map(_.money).sum + money <= 100) {
                      i
                    } else if (
                      ((vipUser
                        .map(_.money)
                        .getOrElse(
                          0
                        ) + todayRemain * 100) - money * 100 * 0.02) < 0
                    ) {
                      throw InvalidException("非法支付、余额不足")
                    } else {
                      i.copy(
                        fee = (i.money * 100 * 0.02).toInt
                      )
                    }
                  } else {
                    i
                  }
              }
          } else {
            Source.single(i)
          }
        })
        .via(OrderStream.add()(c.ctx.system))
        .map(_._1)
        .via(OrderStream.aggregation()(c.ctx.system))
        .via(OrderStream.qrcodeCreate()(c.ctx.system))
        .mapAsync(1) {
          info =>
            if (info._2.qrcode.isDefined || info._2.codeUrl.isDefined) {
              CacheSource(c.ctx.system)
                .cache()
                .put[LocalDateTime](
                  key = "createOrder_" + info._1.platform + openid,
                  value = LocalDateTime.now(),
                  ttl = 60.seconds
                )
                .map(_ => info)(c.ctx.system.executionContext)
            } else {
              CacheSource(c.ctx.system)
                .cache()
                .remove("createOrder_" + info._1.platform + openid)
                .map(_ => info)(c.ctx.system.executionContext)
            }
        }
        .via(OrderStream.notifyOrderCreateStatus()(c.ctx.system))
        .mapAsync(1)(i => {
          if (i._2.code.getOrElse(-1) > 0) {
            logger.error(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.orderCreateFail,
                  "order" -> i._1,
                  "result" -> i._2,
                  "ip" -> c.value.addressInfo.ip,
                  "province" -> c.value.addressInfo.province,
                  "city" -> c.value.addressInfo.city
                )
              ).toJson
            )
            if (i._2.code.getOrElse(0) == 4005179) {
              //当前充值账号已发生变化，请重新填写
              throw DouyinAccountFailException(
                i._2.message.getOrElse("empty error message")
              )
            }
            CacheSource(c.ctx.system)
              .cache()
              .put[String](
                key = "qrcodeCreateFail_" + i._1.platform + openid,
                value = i._2.message.getOrElse("empty error message"),
                ttl = 1.hours
              )
              .map(v => {
                throw DouyinAccountFailException(
                  v
                )
              })(c.ctx.system.executionContext)
          } else if (i._2.qrcode.isEmpty && i._2.codeUrl.isEmpty) {
            logger.error(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.orderCreateFail,
                  "order" -> i._1,
                  "result" -> i._2,
                  "ip" -> c.value.addressInfo.ip,
                  "province" -> c.value.addressInfo.province,
                  "city" -> c.value.addressInfo.city
                )
              ).toJson
            )
            throw new Exception(
              s"${i._1.orderId} qrcode or codeUrl is empty"
            )
          } else {
            logger.info(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.orderCreateOk,
                  "order" -> i._1,
                  "payQrcode" -> i._2.qrcode.getOrElse(
                    ""
                  ),
                  "ip" -> c.value.addressInfo.ip,
                  "province" -> c.value.addressInfo.province,
                  "city" -> c.value.addressInfo.city
                )
              ).toJson
            )
            Future.successful(i)
          }
        })
        .via(OrderStream.downloadQrocdeFile()(c.ctx.system))
        .map((result: (OrderModel.DbInfo, String)) => {
          OrderModel.OrderCreateResponse(
            queryUrl =
              (domain + s"/order/info/" + result._1.orderId),
            qrcodeUrl =
              (domain + s"/file/image?path=" + result._2)
          )
        })
        .recover {
          case e: PayManyException           => throw e
          case e: InvalidException           => throw e
          case e: DouyinAccountFailException => throw e
          case ee => {
            ee.printStackTrace()
            throw new Exception("当前充值人数太多、请稍候再试")
          }
        }
        .idleTimeout(11.seconds)
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )

  val query = fields[SecureContext, RequestInfo](
    moneyMenu,
    orderStatus
  )
  val mutation = fields[SecureContext, RequestInfo](
    orderCreate
  )
}
