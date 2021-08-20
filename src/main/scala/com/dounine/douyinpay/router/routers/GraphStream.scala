package com.dounine.douyinpay.router.routers

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.stream.scaladsl.Source

object GraphStream {

  def sourceSingle()(implicit
      system: ActorSystem[_]
  ): Source[String, NotUsed] = {
    Source
      .single("eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJleHAiOjE2MzE5NDk5ODIsImlhdCI6MTYyOTM1Nzk4Miwib3BlbmlkIjoib05zQjE1cnRrdTU2WnpfdHZfVzBObGdESUYxbyJ9.o1W7ggKVR1HrHZW_ClU50BQMP7n-eIuOJKTPa5e8-0k")

  }

}
