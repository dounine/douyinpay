package com.dounine.douyinpay.tools.util

import akka.actor.typed.ActorSystem

import java.util
import java.util.concurrent.atomic.{AtomicInteger, AtomicLong}
object QrcodeUrlRandom {

  val randomCount: AtomicInteger = new AtomicInteger(0)

  def random()(implicit system: ActorSystem[_]): String = {
    val urls: util.List[String] =
      system.settings.config.getStringList("app.qrcodeUrls")
    urls.get(randomCount.getAndIncrement() % urls.size())
  }

}
