package com.dounine.douyinpay.router.routers.schema

import com.dounine.douyinpay.model.types.service.PayPlatform
import sangria.schema.{EnumType, EnumValue}

object Enums {

  val PayPlatformType = EnumType[PayPlatform.PayPlatform](
    "Platform",
    Some("充值平台"),
    values = List(
      EnumValue(
        "douyin",
        value = PayPlatform.douyin,
        description = Some("抖音")
      ),
      EnumValue(
        "kuaishou",
        value = PayPlatform.kuaishou,
        description = Some("快手")
      ),
      EnumValue(
        "huoshan",
        value = PayPlatform.huoshan,
        description = Some("抖音火山视频")
      ),
      EnumValue(
        "douyu",
        value = PayPlatform.douyu,
        description = Some("斗鱼")
      ),
      EnumValue(
        "huya",
        value = PayPlatform.huya,
        description = Some("虎牙")
      )
    )
  )

}
