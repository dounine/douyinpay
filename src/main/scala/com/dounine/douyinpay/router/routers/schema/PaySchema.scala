package com.dounine.douyinpay.router.routers.schema

import akka.http.scaladsl.{ConnectionContext, Http}
import akka.http.scaladsl.model.{
  ContentTypes,
  HttpEntity,
  HttpMethods,
  HttpRequest,
  HttpResponse
}
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import com.dounine.douyinpay.model.models.{
  AccountModel,
  OrderModel,
  PayModel,
  PayUserInfoModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.service.{
  LogEventKey,
  PayPlatform,
  PayStatus
}
import com.dounine.douyinpay.router.routers.SecureContext
import com.dounine.douyinpay.router.routers.errors.{
  LockedException,
  PayManyException
}
import com.dounine.douyinpay.router.routers.schema.SchemaDef.RequestInfo
import com.dounine.douyinpay.service.{
  AccountStream,
  OpenidStream,
  OrderStream,
  PayStream,
  WechatStream
}
import com.dounine.douyinpay.tools.akka.cache.CacheSource
import com.dounine.douyinpay.tools.json.JsonParse
import com.dounine.douyinpay.tools.util.WeixinPay.Order
import com.dounine.douyinpay.tools.util.{
  MD5Util,
  OpenidPaySuccess,
  Request,
  UUIDUtil,
  WeixinPay
}
import org.slf4j.LoggerFactory
import sangria.macros.derive.{
  DocumentField,
  EnumTypeDescription,
  EnumTypeName,
  ObjectTypeDescription,
  ObjectTypeName,
  deriveEnumType,
  deriveObjectType
}
import sangria.schema._
import org.json4s.Xml._

import java.io.InputStream
import java.security.{KeyStore, SecureRandom}
import java.text.DecimalFormat
import java.time.{LocalDate, LocalDateTime}
import javax.net.ssl.{KeyManagerFactory, SSLContext}
import scala.concurrent.Future
import scala.concurrent.duration._

object PaySchema extends JsonParse {

  private val logger = LoggerFactory.getLogger(PaySchema.getClass)

  val PayResponse =
    deriveObjectType[Unit, AccountModel.PayResponse](
      ObjectTypeName("PayResponse"),
      ObjectTypeDescription("充值信息"),
      DocumentField("orderId", "定单号"),
      DocumentField("appId", "公众号ID"),
      DocumentField("timeStamp", "时间戳,自1970年以来的秒数"),
      DocumentField("nonceStr", "随机字符串"),
      DocumentField("package", "prepay_id返回值"),
      DocumentField("signType", "签名方式"),
      DocumentField("paySign", "签名值")
    )

  val RechargeItem =
    deriveObjectType[Unit, AccountModel.RechargeItem](
      ObjectTypeName("RechargeItem"),
      ObjectTypeDescription("充值金额"),
      DocumentField("money", " 金额")
    )

  val LimitInfo =
    deriveObjectType[Unit, AccountModel.AccountLimit](
      ObjectTypeName("AccountLimit"),
      ObjectTypeDescription("限制信息"),
      DocumentField("cost", "手续费"),
      DocumentField("limitUse", "限制使用"),
      DocumentField("freeUse", "免费额度已使用"),
      DocumentField("balance", "帐户余额")
    )

  val QueryPayResponse =
    deriveObjectType[Unit, AccountModel.QueryPayResponse](
      ObjectTypeName("QueryPayResponse"),
      ObjectTypeDescription("支付信息"),
      DocumentField("pay", "是否已支付"),
      DocumentField("createTime", "创建时间"),
      DocumentField("money", "充值金额"),
      DocumentField("balance", "帐户余额"),
      DocumentField("orderId", "定单号")
    )

  val AccountRechargeResponse = ObjectType(
    name = "AccountRechargeResponse",
    description = "充值信息",
    fields = fields[Unit, AccountModel.AccountRechargeResponse](
      Field(
        name = "list",
        fieldType = ListType(RechargeItem),
        description = Some("充值金额列表"),
        resolve = _.value.list
      ),
      Field(
        name = "balance",
        fieldType = StringType,
        description = Some("余额"),
        resolve = _.value.balance
      ),
      Field(
        name = "vipUser",
        fieldType = BooleanType,
        description = Some("是否是vip用户"),
        resolve = _.value.vipUser
      )
    )
  )

  val BillItem =
    deriveObjectType[Unit, AccountModel.BillItem](
      ObjectTypeName("BillItem"),
      ObjectTypeDescription("帐单"),
      DocumentField("id", "id"),
      DocumentField("money", "金额"),
      DocumentField("canRefund", "是否可退款"),
      DocumentField("refund", "可退款金额"),
      DocumentField("status", "状态"),
      DocumentField("createTime", "创建时间")
    )

  val BillResponse = ObjectType(
    name = "BillResponse",
    description = "帐单信息",
    fields = fields[Unit, AccountModel.BillResponse](
      Field(
        name = "list",
        fieldType = ListType(BillItem),
        description = Some("帐单金额列表"),
        resolve = _.value.list
      ),
      Field(
        name = "sum",
        fieldType = StringType,
        description = Some("总讲"),
        resolve = _.value.sum
      )
    )
  )

  val RefundResponse = ObjectType(
    name = "RefundResponse",
    description = "退款信息",
    fields = fields[Unit, AccountModel.RefundResponse](
      Field(
        name = "msg",
        fieldType = StringType,
        description = Some("退款信息"),
        resolve = _.value.msg
      )
    )
  )

  val MoneyArg = Argument(
    name = "money",
    argumentType = IntType,
    description = "充值金额"
  )

  val OrderIdArg = Argument(
    name = "orderId",
    argumentType = StringType,
    description = "定单号"
  )

  val queryPay = Field[
    SecureContext,
    RequestInfo,
    AccountModel.QueryPayResponse,
    AccountModel.QueryPayResponse
  ](
    name = "queryPay",
    fieldType = QueryPayResponse,
    tags = Authorised :: Nil,
    description = Some("支付定单信息"),
    arguments = OrderIdArg :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      val orderId = c.arg(OrderIdArg)
      implicit val system = c.ctx.system
      Source
        .single(orderId)
        .via(PayStream.query())
        .mapAsync(1) {
          payInfo =>
            {
              if (payInfo.pay == PayStatus.payed) {
                Source
                  .single(
                    payInfo.openid
                  )
                  .via(AccountStream.query())
                  .map(_.get)
                  .zip(
                    Source
                      .single(payInfo.openid)
                      .via(OrderStream.queryOpenidTodayPay())
                  )
                  .map {
                    case (user, todayOrders) => {
                      val commonRemain: Int = 100 - todayOrders.map(_.money).sum
                      val todayRemain: Double =
                        if (commonRemain < 0) 0d else commonRemain * 0.02

                      AccountModel.QueryPayResponse(
                        orderId = orderId,
                        pay = true,
                        createTime = Some(payInfo.createTime.toString),
                        money = Some((payInfo.money / 100).toString),
                        balance = Some(
                          (todayRemain + (user.money / 100.0)).formatted("%.2f")
                        )
                      )
                    }
                  }
                  .runWith(Sink.head)
              } else {
                Future.successful(
                  AccountModel.QueryPayResponse(
                    orderId = orderId,
                    pay = false
                  )
                )
              }
            }
        }
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )

  val rechargeInfo = Field[
    SecureContext,
    RequestInfo,
    AccountModel.AccountRechargeResponse,
    AccountModel.AccountRechargeResponse
  ](
    name = "rechargeInfo",
    fieldType = AccountRechargeResponse,
    tags = Authorised :: Nil,
    description = Some("充值信息"),
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val system = c.ctx.system
      val openid = c.ctx.openid.get
      Source
        .single(c.ctx.openid.get)
        .via(
          AccountStream.query()
        )
        .zip(
          Source
            .single(c.ctx.openid.get)
            .via(OrderStream.queryOpenidTodayPay()(c.ctx.system))
            .zip(
              Source
                .single(c.ctx.openid.get)
                .via(OrderStream.queryOpenidSharedPay())
            )
        )
        .map {
          case (accountUser, (todayOrders, sharePayedMoney)) =>
            val commonRemain: Int =
              (100 + sharePayedMoney
                .getOrElse(0) / 2) - todayOrders.map(_.money).sum
            val todayRemain: Double =
              if (commonRemain < 0) 0d else commonRemain * 0.02
            AccountModel.AccountRechargeResponse(
              list =
                (if (
                   openid == "oNsB15rtku56Zz_tv_W0NlgDIF1o" || openid == "oHUbp6rLcRUSsn9kX5T8WTwyO5XI"
                 ) {
                   if (accountUser.isDefined) {
                     List(0.01, 5, 10, 50, 100, 200, 500)
                   } else {
                     List(0.01, 5, 10, 20)
                   }
                 } else {
                   if (accountUser.isDefined) {
                     List(5.0, 10, 50, 100, 200, 500)
                   } else {
                     List(5.0, 10, 20)
                   }
                 })
                  .map(i =>
                    AccountModel
                      .RechargeItem(
                        if (i < 1) i.toString else i.toInt.toString
                      )
                  ),
              balance =
                (todayRemain + accountUser.map(_.money / 100d).getOrElse(0d))
                  .formatted("%.2f"),
              vipUser = accountUser.isDefined
            )
        }
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )

  val moneyFormat = new DecimalFormat("###,###")

  val billInfo = Field[
    SecureContext,
    RequestInfo,
    AccountModel.BillResponse,
    AccountModel.BillResponse
  ](
    name = "billInfo",
    fieldType = BillResponse,
    tags = Authorised :: Nil,
    description = Some("帐单信息"),
    arguments = Argument(
      name = "date",
      argumentType = StringType,
      description = "日期"
    ) :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val system = c.ctx.system
      val openid = c.ctx.openid.get
      val date = c.arg[String]("date")
      Source
        .single((LocalDate.parse(date), openid))
        .via(
          PayStream.todayPay()
        )
        .zip(
          Source
            .single(
              c.ctx.openid.get
            )
            .via(
              AccountStream.query()
            )
        )
        .map {
          case (todayPay, accountInfo) => {
            val money = accountInfo.map(_.money).getOrElse(0)
            AccountModel.BillResponse(
              list = todayPay
                .map(i => {

                  //支付成功3分钟后、并且在1个小时之前
                  val canRefund =
                    i.pay == PayStatus.payed && money >= i.money && i.createTime
                      .plusHours(1)
                      .isAfter(LocalDateTime.now())

                  AccountModel.BillItem(
                    id = i.id,
                    money = i.money.toString,
                    canRefund = canRefund,
                    status = i.pay match {
                      case PayStatus.normal    => "未支付"
                      case PayStatus.payed     => ""
                      case PayStatus.payerr    => "错误"
                      case PayStatus.refund    => "已退款"
                      case PayStatus.refunding => "退款中"
                      case PayStatus.cancel    => "撤消"
                    },
                    refund =
                      if (canRefund)
                        Some(
                          Math
                            .min(
                              money,
                              i.money
                            )
                            .toString
                        )
                      else None,
                    createTime = i.createTime.toString.replace("T", " ")
                  )
                })
                .toList
                .sortBy(_.createTime)(Ordering.String.reverse),
              sum = moneyFormat.format(
                todayPay
                  .map(_.money / 100)
                  .sum
              )
            )
          }
        }
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )

  val refund = Field[
    SecureContext,
    RequestInfo,
    AccountModel.RefundResponse,
    AccountModel.RefundResponse
  ](
    name = "refund",
    fieldType = RefundResponse,
    tags = Authorised :: Nil,
    description = Some("申请退款"),
    arguments = Argument(
      name = "payId",
      argumentType = StringType,
      description = "退款定单"
    ) :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val system = c.ctx.system
      val openid = c.ctx.openid.get
      val payId = c.arg[String]("payId")
      val appid = c.ctx.appid.get
      Source
        .single(payId)
        .via(
          PayStream.query()
        )
        .zip(
          Source
            .single(
              openid
            )
            .via(
              AccountStream.query()
            )
        )
        .flatMapConcat {
          case (payInfo, accountInfo) => {
            val money = accountInfo.map(_.money).getOrElse(0)
            //支付成功3分钟后、并且在1个小时之前
            if (
              !(payInfo.pay == PayStatus.payed && money >= payInfo.money && payInfo.createTime
                .plusHours(1)
                .isAfter(LocalDateTime.now()))
            ) {
              logger.error(
                Map(
                  "time" -> System.currentTimeMillis(),
                  "data" -> Map(
                    "event" -> LogEventKey.refundFail,
                    "msg" -> "申请退款失败、条件不满足。",
                    "openid" -> openid,
                    "appid" -> appid,
                    "payInfo" -> payInfo,
                    "accountInfo" -> accountInfo,
                    "ip" -> c.value.addressInfo.ip,
                    "province" -> c.value.addressInfo.province,
                    "city" -> c.value.addressInfo.city
                  )
                ).toJson
              )
              throw new Exception("申请退款失败、条件不满足。")
            } else {
              logger.info(
                Map(
                  "time" -> System.currentTimeMillis(),
                  "data" -> Map(
                    "event" -> LogEventKey.refundRequest,
                    "openid" -> openid,
                    "appid" -> appid,
                    "payInfo" -> payInfo,
                    "accountInfo" -> accountInfo,
                    "ip" -> c.value.addressInfo.ip,
                    "province" -> c.value.addressInfo.province,
                    "city" -> c.value.addressInfo.city
                  )
                ).toJson
              )
            }

            val refundMoney = Math
              .min(
                money,
                payInfo.money
              )

            Source
              .single(
                AccountModel.AccountInfo(
                  openid = openid,
                  money = refundMoney
                )
              )
              .via(
                AccountStream.decrmentMoneyToAccount()
              )
              .map(i => {
                (payInfo, accountInfo, refundMoney)
              })
          }
        }
        .mapAsync(1) {
          case (payInfo, accountInfo, refundMoney) => {
            val mch_id =
              c.ctx.system.settings.config
                .getString(s"app.wechat.${appid}.pay.mchid")

            val http = Http(system)
            val password: Array[Char] =
              mch_id.toCharArray

            val ks: KeyStore = KeyStore.getInstance("PKCS12")
            val keystore: InputStream =
              WeixinPay.getClass.getResourceAsStream(s"/api/${mch_id}/apiclient_cert.p12")

            require(keystore != null, "Keystore required!")
            ks.load(keystore, password)

            val keyManagerFactory: KeyManagerFactory =
              KeyManagerFactory
                .getInstance(KeyManagerFactory.getDefaultAlgorithm)
            keyManagerFactory.init(ks, password)

            val sslContext: SSLContext = SSLContext.getInstance("TLS")
            sslContext.init(
              keyManagerFactory.getKeyManagers,
              null,
              new SecureRandom
            )
            val https = ConnectionContext.httpsClient(sslContext)

            val data = Map(
              "appid" -> appid,
              "mch_id" -> mch_id,
              "nonce_str" -> UUIDUtil.uuid(),
              "out_trade_no" -> payInfo.id,
              "out_refund_no" -> payInfo.id,
              "refund_desc" -> "申请退款",
              "total_fee" -> refundMoney.toString,
              "refund_fee" -> refundMoney.toString,
              "notify_url" -> "https://backup.61week.com/api/wechat/refund/notify"
            )
            val signData = WeixinPay.generateSignature(
              data,
              appid
            )
            implicit val ec = c.ctx.system.executionContext
            http
              .singleRequest(
                HttpRequest(
                  method = HttpMethods.POST,
                  uri = "https://api.mch.weixin.qq.com/secapi/pay/refund",
                  entity = HttpEntity(
                    ContentTypes.`text/xml(UTF-8)`,
                    signData.toXml(Some("xml"))
                  )
                ),
                https
              )
              .flatMap {
                case HttpResponse(code, value, entity, protocol) =>
                  entity.dataBytes
                    .runFold(ByteString.empty)(_ ++ _)
                    .map(_.utf8String)
                    .map(_.childXmlTo[AccountModel.WechatRefundResponse])
              }
              .map(r => {
                if (r.return_code == "SUCCESS") {
                  logger.info(
                    Map(
                      "time" -> System.currentTimeMillis(),
                      "data" -> Map(
                        "event" -> LogEventKey.refundOk,
                        "openid" -> openid,
                        "appid" -> appid,
                        "payInfo" -> payInfo,
                        "accountInfo" -> accountInfo,
                        "ip" -> c.value.addressInfo.ip,
                        "province" -> c.value.addressInfo.province,
                        "city" -> c.value.addressInfo.city
                      )
                    ).toJson
                  )
                  AccountModel.RefundResponse(
                    msg = "申请成功、正在退款中..."
                  )
                } else {
                  logger.error(
                    Map(
                      "time" -> System.currentTimeMillis(),
                      "data" -> Map(
                        "event" -> LogEventKey.refundFail,
                        "msg" -> r.return_msg,
                        "openid" -> openid,
                        "appid" -> appid,
                        "payInfo" -> payInfo,
                        "accountInfo" -> accountInfo,
                        "ip" -> c.value.addressInfo.ip,
                        "province" -> c.value.addressInfo.province,
                        "city" -> c.value.addressInfo.city
                      )
                    ).toJson
                  )
                  AccountModel.RefundResponse(
                    msg = r.return_msg
                  )
                }
              })
          }
        }
        .flatMapConcat((result: AccountModel.RefundResponse) => {
          Source
            .single(payId)
            .via(
              PayStream.updateStatus(PayStatus.refunding)
            )
            .map(_ => result)
        })
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )

  val limitInfo = Field[
    SecureContext,
    RequestInfo,
    AccountModel.AccountLimit,
    AccountModel.AccountLimit
  ](
    name = "limitInfo",
    fieldType = LimitInfo,
    tags = Authorised :: Nil,
    description = Some("查询限制信息"),
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val system = c.ctx.system

      Source
        .single(c.ctx.openid.get)
        .via(
          OrderStream.queryOpenidTodayPay()
        )
        .zip(
          Source
            .single(c.ctx.openid.get)
            .via(
              AccountStream.query()
            )
        )
        .map {
          case (orders, account) => {
            AccountModel.AccountLimit(
              cost = "0.02",
              limitUse = "100",
              freeUse = orders.map(_.money).sum.toString,
              balance =
                (account.map(_.money).getOrElse(0) / 100.0).formatted("%.2f")
            )
          }
        }
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )

  val createPay = Field[
    SecureContext,
    RequestInfo,
    AccountModel.PayResponse,
    AccountModel.PayResponse
  ](
    name = "pay",
    fieldType = PayResponse,
    tags = Authorised :: Nil,
    description = Some("充值金额"),
    arguments = MoneyArg :: Nil,
    resolve = (c: Context[SecureContext, RequestInfo]) => {
      implicit val s = c.ctx.system
      val money = c.arg(MoneyArg)
      val appid = c.ctx.appid.get
      val mch_id =
        c.ctx.system.settings.config.getString(s"app.wechat.${appid}.pay.mchid")
      logger.info(
        Map(
          "time" -> System.currentTimeMillis(),
          "data" -> Map(
            "event" -> LogEventKey.accountPayRequest,
            "mch_id" -> mch_id,
            "money" -> money,
            "openid" -> c.ctx.openid.get,
            "ip" -> c.value.addressInfo.ip,
            "province" -> c.value.addressInfo.province,
            "city" -> c.value.addressInfo.city
          )
        ).toJson
      )
      val orderId = UUIDUtil.uuid()
      val order = WeixinPay.Order(
        appid = appid,
        body = s"充值 - ${money / 100.0}元",
        mch_id = mch_id,
        nonce_str = UUIDUtil.uuid(),
        notify_url = "https://backup.61week.com/api/wechat/pay/notify",
        openid = c.ctx.openid.get,
        out_trade_no = orderId,
        spbill_create_ip = "183.6.105.141",
        total_fee = money.toString,
        trade_type = "JSAPI",
        sign_type = Some("MD5"),
        sign = None
      )
      implicit val ec = c.ctx.system.executionContext

      Source
        .single(
          PayModel.PayInfo(
            id = orderId,
            money = money,
            openid = c.ctx.openid.get,
            pay = PayStatus.normal,
            payTime = LocalDateTime.now(),
            createTime = LocalDateTime.now()
          )
        )
        .via(PayStream.createPay())
        .mapAsync(1) { _ =>
          WeixinPay
            .unifiedOrder(order)
            .map(i=>{
              println(i)
              i
            })
        }
        .map(map => {
          require(
            WeixinPay.isSignatureValid(
              data = map,
              sign = map("sign")
            ),
            "sign invalid"
          )
          val data = WeixinPay.generateSignature(
            data = Map(
              "appId" -> order.appid,
              "timeStamp" -> (System.currentTimeMillis() / 1000).toString,
              "nonceStr" -> UUIDUtil.uuid(),
              "package" -> ("prepay_id=" + map("prepay_id")),
              "signType" -> "MD5"
            ),
            appid = order.appid
          )
          AccountModel.PayResponse(
            orderId = orderId,
            appId = data("appId"),
            timeStamp = data("timeStamp"),
            nonceStr = data("nonceStr"),
            `package` = data("package"),
            signType = data("signType"),
            paySign = data("sign")
          )
        })
        .runWith(Sink.head)(SystemMaterializer(c.ctx.system).materializer)
    }
  )
  val query = fields[SecureContext, RequestInfo](
    rechargeInfo,
    billInfo,
    queryPay,
    limitInfo
  )
  val mutation = fields[SecureContext, RequestInfo](
    createPay,
    refund
  )
}
