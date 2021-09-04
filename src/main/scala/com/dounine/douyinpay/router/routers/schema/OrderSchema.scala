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
  InvalidException,
  LockedException,
  PayManyException
}
import com.dounine.douyinpay.router.routers.schema.SchemaDef.RequestInfo
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
      DocumentField("commonEnought", "常规用户是否足够充值"),
      DocumentField("vipEnought", "vip用户是否足够充值")
    )

  val MoneyMenuResponse = ObjectType(
    name = "MoneyMenuResponse",
    description = "金额信息",
    fields = fields[Unit, OrderModel.MoneyMenuResponse](
      Field(
        name = "list",
        fieldType = ListType(MoneyMenuItem),
        description = Some("列表"),
        resolve = _.value.list
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

  val commonUserMoneys = List(
    6, 10, 30, 66, 88, 100
  )
  val vipUserMoneys = List(
    6, 30, 66, 88, 288, 688, 1888, 6666, 8888
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
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val system = c.ctx.system
      val openid = c.ctx.openid.get
      Source
        .single(c.ctx.openid.get)
        .via(AccountStream.query()(c.ctx.system))
        .zip(
          Source
            .single(c.ctx.openid.get)
            .via(OrderStream.queryOpenidTodayPay()(c.ctx.system))
        )
        .zipWith(
          Source
            .single(c.ctx.openid.get)
            .via(
              OpenidStream.query()
            )
        ) { (pre, next) =>
          (
            pre._1,
            pre._2,
            next
          )
        }
        .map {
          case (
                vipUser: Option[AccountModel.AccountInfo],
                orders: Seq[OrderModel.DbInfo],
                wechatInfo: Option[OpenidModel.OpenidInfo]
              ) => {
            vipUser match {
              case Some(vip) =>
                val commonRemain: Int = 100 - orders.map(_.money).sum
                val accountMoney = vip.money
                val list = vipUserMoneys
                  .map(money =>
                    (moneyFormat.format(money), volumnFormat.format(money * 10))
                  )
                  .map(tp2 => {
                    val money = moneyFormat.parse(tp2._1).intValue()
                    OrderModel.MoneyMenuItem(
                      money = tp2._1,
                      volumn = tp2._2,
                      commonEnought = commonRemain - money >= 0,
                      vipEnought = Some(
                        accountMoney - money * 100 * 0.02 >= 0
                      )
                    )
                  })
                OrderModel.MoneyMenuResponse(
                  list = list,
                  targetUser = true,
                  commonRemain = commonRemain,
                  vipRemain = Some((vip.money / 100.0).formatted("%.2f"))
                )
              case None =>
                if (
                  LocalDate
                    .now()
                    .atStartOfDay()
                    .isAfter(
                      wechatInfo.get.createTime
                        .plusDays(3)
                    ) && OpenidPaySuccess
                    .query(openid) > 2
                ) {
                  val commonRemain: Int = 100 - orders.map(_.money).sum
                  val list = commonUserMoneys
                    .map(money =>
                      (
                        moneyFormat.format(money),
                        volumnFormat.format(money * 10)
                      )
                    )
                    .map(tp2 => {
                      val money = moneyFormat.parse(tp2._1).intValue()
                      OrderModel.MoneyMenuItem(
                        money = tp2._1,
                        volumn = tp2._2,
                        commonEnought = commonRemain - money >= 0
                      )
                    })
                  OrderModel.MoneyMenuResponse(
                    list = list,
                    targetUser = true,
                    commonRemain = commonRemain
                  )
                } else {
                  OrderModel.MoneyMenuResponse(
                    list = vipUserMoneys
                      .map(money =>
                        (
                          moneyFormat.format(money),
                          volumnFormat.format(money * 10)
                        )
                      )
                      .map(tp2 => {
                        OrderModel.MoneyMenuItem(
                          money = tp2._1,
                          volumn = tp2._2,
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
    arguments = Argument(
      name = "orderId",
      argumentType = StringType,
      description = "定单ID"
    ) :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) =>
      ServiceSingleton
        .get(classOf[OrderService])
        .queryOrderStatus(c.arg[String]("orderId"))
  )

  val OrderCreateResponse =
    deriveObjectType[Unit, OrderModel.OrderCreateResponse](
      ObjectTypeName("OrderCreateResponse"),
      ObjectTypeDescription("定单创建响应"),
      DocumentField("queryUrl", "定单状态查询地扯"),
      DocumentField("qrcodeUrl", "支付二维码地扯")
    )

  val MoneyArg = Argument(
    name = "money",
    argumentType = StringType,
    description = "充值金额"
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
    arguments = Argument(
      name = "id",
      argumentType = StringType,
      description = "抖音帐号"
    ) :: MoneyArg :: Argument(
      name = "volumn",
      argumentType = StringType,
      description = "充值抖币"
    ) :: Argument(
      name = "ccode",
      argumentType = StringType,
      description = "渠道"
    ) :: Argument(
      name = "sign",
      argumentType = StringType,
      description = "签名md5((id,money,volumn,openid).sort.join(''))"
    ) :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val system = c.ctx.system
      val money = moneyFormat.parse(c.arg(MoneyArg)).intValue()
      val openid = c.ctx.openid.get
      Source
        .single(
          OrderModel.Recharge(
            id = c.arg[String]("id"),
            money = c.arg[String]("money"),
            volumn = c.arg[String]("volumn"),
            ccode = c.arg[String]("ccode"),
            sign = c.arg[String]("sign")
          )
        )
        .map(_ -> c.ctx.openid.get)
        .mapAsync(1) {
          tp2 =>
            CacheSource(c.ctx.system)
              .cache()
              .get[LocalDateTime]("createOrder_" + openid)
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
                i._1.money,
                i._1.volumn,
                i._1.ccode,
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
                  "payAccount" -> i._1.id,
                  "payMoney" -> i._1.money,
                  "payVolumn" -> i._1.volumn,
                  "payCcode" -> i._1.ccode,
                  "sign" -> i._1.sign,
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
            platform = PayPlatform.douyin,
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
          if (
            OpenidPaySuccess
              .query(openid) > 2
          ) {
            Source
              .single(openid)
              .via(
                OrderStream.queryOpenidTodayPay()
              )
              .zip(
                Source
                  .single(openid)
                  .via(AccountStream.query())
              )
              .zipWith(
                Source
                  .single(openid)
                  .via(OpenidStream.query())
              )((pre, next) => (pre._1, pre._2, next))
              .map {
                case (value, maybeInfo, wechatUser) =>
                  if (
                    LocalDate
                      .now()
                      .atStartOfDay()
                      .isAfter(
                        wechatUser.get.createTime
                          .plusDays(3)
                      )
                  ) {
                    if (value.map(_.money).sum + money <= 100) {
                      i
                    } else if (
                      (maybeInfo
                        .map(_.money)
                        .getOrElse(0) - money * 100 * 0.02) < 0
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
            info._2.qrcode match {
              case Some(value) =>
                CacheSource(c.ctx.system)
                  .cache()
                  .put[LocalDateTime](
                    key = "createOrder_" + openid,
                    value = LocalDateTime.now(),
                    ttl = 60.seconds
                  )
                  .map(_ => info)(c.ctx.system.executionContext)
              case None => {
                CacheSource(c.ctx.system)
                  .cache()
                  .remove("createOrder_" + openid)
                  .map(_ => info)(c.ctx.system.executionContext)
              }
            }
        }
        .via(OrderStream.notifyOrderCreateStatus()(c.ctx.system))
        .map(i => {
          if (i._2.qrcode.isEmpty) {
            logger.error(
              Map(
                "time" -> System.currentTimeMillis(),
                "data" -> Map(
                  "event" -> LogEventKey.orderCreateFail,
                  "order" -> i._1,
                  "payQrcode" -> "",
                  "payMessage" -> i._2.message.getOrElse(
                    ""
                  ),
                  "paySetup" -> i._2.setup.getOrElse(""),
                  "ip" -> c.value.addressInfo.ip,
                  "province" -> c.value.addressInfo.province,
                  "city" -> c.value.addressInfo.city
                )
              ).toJson
            )
            throw new Exception(
              s"${i._1.orderId} qrcode is empty"
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
          }
          i
        })
        .map(t => (t._1, t._2.qrcode.get))
        .via(OrderStream.downloadQrocdeFile()(c.ctx.system))
        .map((result: (OrderModel.DbInfo, String)) => {
          val config = c.ctx.system.settings.config.getConfig("app")
          val routerPrefix = config.getString("routerPrefix")
          OrderModel.OrderCreateResponse(
            queryUrl =
              (c.value.scheme + "://douyinapi.61week.com" + s"/${routerPrefix}/order/info/" + result._1.orderId),
            qrcodeUrl =
              (c.value.scheme + "://douyinapi.61week.com" + s"/${routerPrefix}/file/image?path=" + result._2)
          )
        })
        .recover {
          case e: PayManyException => throw e
          case e: InvalidException => throw e
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
