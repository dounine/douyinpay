package com.dounine.douyinpay.router.routers.schema

import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import com.dounine.douyinpay.model.models.{
  AccountModel,
  OrderModel,
  PayModel,
  PayUserInfoModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.service.{LogEventKey, PayPlatform}
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

import java.text.DecimalFormat
import java.time.LocalDateTime
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
        .via(PayStream.payQuery())
        .mapAsync(1) {
          payInfo =>
            {
              if (payInfo.pay) {
                Source
                  .single(
                    payInfo.openid
                  )
                  .via(AccountStream.query())
                  .map(_.get)
                  .map(user => {
                    AccountModel.QueryPayResponse(
                      orderId = orderId,
                      pay = true,
                      createTime = Some(payInfo.createTime.toString),
                      money = Some((payInfo.money / 100).toString),
                      balance = Some((user.money / 100.0).formatted("%.2f"))
                    )
                  })
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
        .map {
          case Some(value) =>
            AccountModel.AccountRechargeResponse(
              list = (if (openid == "oNsB15rtku56Zz_tv_W0NlgDIF1o")
                        List(0.01, 5, 10, 50, 100, 200, 500)
                      else
                        List(5.0, 10, 50, 100, 200, 500))
                .map(i =>
                  AccountModel
                    .RechargeItem(
                      if (i < 1) i.toString else i.toInt.toString
                    )
                ),
              balance = (value.money / 100.0).formatted("%.2f")
            )
          case None =>
            AccountModel.AccountRechargeResponse(
              list = List(0.01, 5, 10, 50, 100, 200, 500)
                .map(i => AccountModel.RechargeItem(i.toString)),
              balance = "0.00"
            )
        }
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
              balance = account
                .map(_.money)
                .getOrElse(BigDecimal("0.00"))
                .formatted("%.2f")
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
        notify_url = "https://lake.61week.com/api/wechat/pay/notify",
        openid = c.ctx.openid.get,
        out_trade_no = orderId,
        spbill_create_ip = "183.6.105.141",
        total_fee = money.toString,
        trade_type = "JSAPI",
        sign_type = Some("MD5"),
        sign = None
      )

      Source
        .single(
          PayModel.PayInfo(
            id = orderId,
            money = money,
            openid = c.ctx.openid.get,
            pay = false,
            payTime = LocalDateTime.now(),
            createTime = LocalDateTime.now()
          )
        )
        .via(PayStream.createPay())
        .mapAsync(1) { _ =>
          WeixinPay
            .unifiedOrder(order)
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
    queryPay,
    limitInfo
  )
  val mutation = fields[SecureContext, RequestInfo](
    createPay
  )
}
