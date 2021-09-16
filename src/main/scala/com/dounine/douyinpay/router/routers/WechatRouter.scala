package com.dounine.douyinpay.router.routers

import akka.actor
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.http.caching.LfuCache
import akka.http.caching.scaladsl.{Cache, CachingSettings}
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives.{concat, _}
import akka.http.scaladsl.server._
import akka.stream._
import akka.stream.scaladsl.{Sink, Source}
import akka.util.ByteString
import ch.megard.akka.http.cors.scaladsl.CorsDirectives._
import com.dounine.douyinpay.behaviors.engine.AccessTokenBehavior
import com.dounine.douyinpay.model.models.{
  AccountModel,
  BaseSerializer,
  OpenidModel,
  WechatModel
}
import com.dounine.douyinpay.model.types.service.{LogEventKey, PayStatus}
import com.dounine.douyinpay.service.{
  AccountStream,
  OpenidStream,
  OrderStream,
  PayStream,
  WechatStream
}
import com.dounine.douyinpay.tools.util.{
  DecodeUtil,
  DingDing,
  IpUtils,
  MD5Util,
  OpenidPaySuccess,
  WeixinPay
}
import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.digest.DigestUtils
import org.slf4j.{Logger, LoggerFactory}

import java.net.{Inet4Address, InetAddress, URLEncoder}
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import javax.crypto.{Cipher, KeyGenerator}
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.Try
import scala.xml.NodeSeq

class WechatRouter()(implicit system: ActorSystem[_])
    extends SuportRouter
    with ScalaXmlSupport {

  case class Hello(name: String) extends BaseSerializer
  private final val logger: Logger =
    LoggerFactory.getLogger(classOf[WechatRouter])
  implicit val materializer: Materializer = SystemMaterializer(
    system
  ).materializer
  implicit val actorSystem: actor.ActorSystem = materializer.system
  implicit val executionContext: ExecutionContextExecutor =
    materializer.executionContext

  val config = system.settings.config.getConfig("app")

  val sharding = ClusterSharding(system)

  val keyFunction: PartialFunction[RequestContext, Uri] = {
    case r: RequestContext => r.request.uri
  }

  val defaultCachingSettings = CachingSettings(system)
  val lfuCacheSettings = defaultCachingSettings.lfuCacheSettings
    .withInitialCapacity(100)
    .withMaxCapacity(1000)
    .withTimeToLive(3.days)
    .withTimeToIdle(1.days)

  val cachingSettings =
    defaultCachingSettings.withLfuCacheSettings(lfuCacheSettings)
  val lfuCache: Cache[Uri, RouteResult] = LfuCache(cachingSettings)

  val domain = config.getString("file.domain")
  val http = Http(system)
  val timeFormatter = DateTimeFormatter.ofPattern("yy-MM-dd HH:mm:ss")

  val route: Route =
    cors() {
      pathPrefix("wechat") {
        concat(
          get {
            path("into" / "from" / Segment) {
              ccode: String =>
                extractClientIP {
                  ip =>
                    extractRequest {
                      request =>
                        val scheme: String = request.headers
                          .map(i => i.name() -> i.value())
                          .toMap
                          .getOrElse("X-Scheme", request.uri.scheme)

                        val (province, city) =
                          IpUtils.convertIpToProvinceCity(ip.getIp())
                        logger.info(
                          Map(
                            "time" -> System.currentTimeMillis(),
                            "data" -> Map(
                              "event" -> LogEventKey.fromCcode,
                              "ccode" -> ccode,
                              "ip" -> ip.getIp(),
                              "province" -> province,
                              "city" -> city
                            )
                          ).toJson
                        )
                        val params: String = Map(
                          "appid" -> "wxc1a77335b1dd223a",
                          "redirect_uri" -> URLEncoder.encode(
                            (scheme + "://" + domain) + s"/?ccode=${ccode}&platform=douyin&appid=wxc1a77335b1dd223a",
                            "utf-8"
                          ),
                          "response_type" -> "code",
                          "scope" -> "snsapi_base",
                          "state" -> "wxc1a77335b1dd223a"
                        ).map(i => s"${i._1}=${i._2}")
                          .mkString("&")
                        redirect(
                          s"https://open.weixin.qq.com/connect/oauth2/authorize?${params}#wechat_redirect",
                          StatusCodes.PermanentRedirect
                        )
                    }
                }
            }
          },
          get {
            path("into" / "from" / Segment / Segment) {
              (appid: String, ccode: String) =>
                extractClientIP {
                  ip =>
                    extractRequest {
                      request =>
                        val scheme: String = request.headers
                          .map(i => i.name() -> i.value())
                          .toMap
                          .getOrElse("X-Scheme", request.uri.scheme)

                        val (province, city) =
                          IpUtils.convertIpToProvinceCity(ip.getIp())
                        logger.info(
                          Map(
                            "time" -> System.currentTimeMillis(),
                            "data" -> Map(
                              "event" -> LogEventKey.fromCcode,
                              "appid" -> appid,
                              "ccode" -> ccode,
                              "ip" -> ip.getIp(),
                              "province" -> province,
                              "city" -> city
                            )
                          ).toJson
                        )
                        val params: String = Map(
                          "appid" -> appid,
                          "redirect_uri" -> URLEncoder.encode(
                            (scheme + "://" + domain) + s"/?ccode=${ccode}&platform=douyin&appid=${appid}",
                            "utf-8"
                          ),
                          "response_type" -> "code",
                          "scope" -> "snsapi_base",
                          "state" -> appid
                        ).map(i => s"${i._1}=${i._2}")
                          .mkString("&")
                        redirect(
                          s"https://open.weixin.qq.com/connect/oauth2/authorize?${params}#wechat_redirect",
                          StatusCodes.PermanentRedirect
                        )
                    }
                }
            }
          },
          post {
            path("pay" / "notify") {
              entity(as[NodeSeq]) {
                data: NodeSeq =>
                  val notifyResponse =
                    data.childXmlTo[AccountModel.NotifyResponse]
                  require(
                    WeixinPay.isSignatureValid(
                      data = data.childXmlTo[Map[String, String]],
                      sign = notifyResponse.sign
                    ),
                    "notify sign error"
                  )

                  logger
                    .info("notify pay success -> {}", notifyResponse.toJson)

                  val wechat = system.settings.config.getConfig("app.wechat")
                  val result = Source
                    .single(notifyResponse.out_trade_no)
                    .via(PayStream.paySuccess())
                    .flatMapConcat((result: Boolean) =>
                      if (result) {
                        Source
                          .single(notifyResponse.openid)
                          .via(
                            OpenidStream.query()
                          )
                          .zip(
                            Source
                              .single(notifyResponse.openid)
                              .via(
                                AccountStream.query()
                              )
                          )
                          .zipWith(
                            Source
                              .single(notifyResponse.openid)
                              .via(
                                PayStream.queryOpenidPaySum()
                              )
                          )((sum, next) => {
                            (sum._1, sum._2, next)
                          })
                          .mapAsync(1) {
                            case (userInfo, vipUser, wechatPays) =>
                              val payInfo =
                                OpenidPaySuccess.query(notifyResponse.openid)
                              DingDing
                                .sendMessageFuture(
                                  DingDing.MessageType.wechatPay,
                                  data = DingDing.MessageData(
                                    markdown = DingDing.Markdown(
                                      title = "支付通知",
                                      text = s"""
                                                |## ${if (vipUser.isEmpty) "首充"
                                      else "复购"}
                                                | - 公众号: ${wechat.getString(
                                        s"${notifyResponse.appid}.name"
                                      )}
                                                | - 总充值金额: ${payInfo.money}
                                                | - 总充值次数: ${payInfo.count}
                                                | - 来源渠道：${userInfo.get.ccode}
                                                | - 总支付金额：${wechatPays
                                        .filter(_.pay == PayStatus.payed)
                                        .map(_.money / 100)
                                        .sum + (notifyResponse.total_fee / 100)} 元
                                                | - 总支付次数：${wechatPays
                                        .filter(_.pay == PayStatus.payed)
                                        .size + 1}
                                                | - 帐户余额：${vipUser
                                        .map(_.money / 100d)
                                        .getOrElse(0d) + (notifyResponse.total_fee / 100)}
                                                | - 注册时间：${java.time.Duration
                                        .between(
                                          userInfo.get.createTime,
                                          LocalDateTime.now()
                                        )
                                        .toDays} 天前
                                                | - openid: ${notifyResponse.openid}
                                                | - 通知时间: ${LocalDateTime
                                        .now()
                                        .format(timeFormatter)}
                                                |""".stripMargin
                                    )
                                  )
                                )
                                .map(_ =>
                                  AccountModel.AccountInfo(
                                    openid = notifyResponse.openid,
                                    money = notifyResponse.total_fee.toInt
                                  )
                                )
                          }
                          .via(AccountStream.incrmentMoneyToAccount())
                          .map((i: Boolean) => {
                            logger
                              .info("incrmentMoneyToAccount success -> {}", i)
                            i
                          })
                      } else {
                        logger.error("notify pay update is payed")
                        Source.single(false)
                      }
                    )
                    .map(i => {
                      xmlResponse(
                        Map(
                          "return_code" -> "SUCCESS",
                          "return_msg" -> "OK"
                        )
                      )
                    })
                    .runWith(Sink.head)
                  complete(
                    result
                  )
              }
            }
          },
          post {
            path("refund" / "notify") {
              entity(as[NodeSeq]) {
                data: NodeSeq =>
                  val notifyResponse =
                    data.childXmlTo[AccountModel.RefundNotifyResponse]

                  logger
                    .info("refund success -> {}", notifyResponse.toJson)

                  val key = system.settings.config
                    .getString(s"app.wechat.${notifyResponse.appid}.pay.key")

                  DecodeUtil
                    .decryptData(
                      notifyResponse.req_info,
                      MD5Util.md5(key)
                    )
                    .map(_.childXmlTo[AccountModel.RefundNotifySuccessDetail])
                    .fold(
                      fa => {
                        fa.printStackTrace()
                        throw fa
                      },
                      (fu: AccountModel.RefundNotifySuccessDetail) => {
                        logger.info("refund notify info -> {}", fu.toJson)
                        fu
                      }
                    )

                  val wechatResponse = Source
                    .single(
                      DecodeUtil
                        .decryptData(
                          notifyResponse.req_info,
                          MD5Util.md5(key)
                        )
                        .map(
                          _.childXmlTo[AccountModel.RefundNotifySuccessDetail]
                        )
                        .fold(
                          fa => {
                            throw fa
                          },
                          (fu: AccountModel.RefundNotifySuccessDetail) => fu
                        )
                    )
                    .flatMapConcat(result => {
                      Source
                        .single(result.out_refund_no)
                        .via(
                          PayStream.query()
                        )
                        .flatMapConcat(r => {
                          if (r.pay == PayStatus.refunding) {
                            Source
                              .single(result.out_refund_no)
                              .via(PayStream.updateStatus(PayStatus.refund))
                          } else {
                            logger.error("重复退款回调")
                            Source.single(false, 0)
                          }
                        })
                    })
                    .map(r => {
                      xmlResponse(
                        Map(
                          "return_code" -> "SUCCESS",
                          "return_msg" -> "OK"
                        )
                      )
                    })
                    .runWith(Sink.head)

                  complete(
                    wechatResponse
                  )
              }
            }
          },
          post {
            path("auth" / Segment) {
              appid =>
                entity(as[NodeSeq]) {
                  data =>
                    val message = WechatModel.WechatMessage.fromXml(appid, data)
                    logger.info(
                      Map(
                        "time" -> System.currentTimeMillis(),
                        "data" -> Map(
                          "event" -> LogEventKey.wechatMessage,
                          "appid" -> appid,
                          "appname" -> config.getString(
                            s"wechat.${appid}.name"
                          ),
                          "message" -> message
                        )
                      ).toJson
                    )
                    val result = Source
                      .single(message)
                      .via(WechatStream.notifyMessage())
                      .runWith(Sink.head)
                    complete(result)
                }
            }
          },
          get {
            path("auth" / Segment) {
              appid =>
                parameters("signature", "timestamp", "nonce", "echostr") {
                  (
                      signature: String,
                      timestamp: String,
                      nonce: String,
                      echostr: String
                  ) =>
                    {
                      val auth = config.getString(s"wechat.${appid}.auth")
                      val sortArray: String =
                        Array(
                          auth,
                          timestamp,
                          nonce
                        ).sorted.mkString("")
                      if (
                        signature == DigestUtils.sha1Hex(sortArray.mkString(""))
                      ) {
                        complete(
                          HttpResponse(
                            StatusCodes.OK,
                            entity = HttpEntity(
                              ContentTypes.`text/plain(UTF-8)`,
                              echostr
                            )
                          )
                        )
                      } else {
                        fail("fail")
                      }
                    }
                }
            }
          }
        )
      }
    }
}
