package com.dounine.douyinpay.router.routers

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.Source

object GraphStream {

  def sourceSingle()(implicit
      system: ActorSystem[_]
  ): Source[String, NotUsed] = {
    Source
      .single("hello")

  }

}
