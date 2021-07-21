package com.dounine.douyinpay.tools.json

import org.json4s.Formats

trait ActorSerializerSuport extends JsonParse {

  override implicit val formats: Formats =
    JsonSuport.formats + JsonSuport.ActorRefSerializer
}
