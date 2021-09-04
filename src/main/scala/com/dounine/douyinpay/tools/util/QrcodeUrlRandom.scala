package com.dounine.douyinpay.tools.util

import akka.actor.typed.ActorSystem

import java.util
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
object QrcodeUrlRandom {

  val randomCount: AtomicInteger = new AtomicInteger(0)

  def random()(implicit system: ActorSystem[_]): String = {
    val urls: Array[String] =
      system.settings.config.getString("app.qrcodeUrls").split(",")

    urls(randomCount.getAndIncrement() % urls.length)
  }

}
