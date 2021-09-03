package com.dounine.douyinpay.tools.json

import de.heikoseeberger.akkahttpjson4s.Json4sSupport
import org.json4s.Formats
import org.json4s.JsonAST.JValue
import org.json4s.jackson.Serialization
import org.json4s.native.Serialization.{read, write}
import org.json4s.native.JsonMethods._

import scala.xml.{NodeSeq, XML}

trait JsonParse extends Json4sSupport {
  implicit val serialization: Serialization.type = JsonSuport.serialization
  implicit val formats: Formats = JsonSuport.formats

  implicit class ToJson(data: Any) {
    def toJson: String = {
      write(data)
    }

    def toMap[T: Manifest]: Map[String, T] = {
      data.toJson.jsonTo[Map[String, T]]
    }

    def toJValue: JValue = {
      data match {
        case v: String if v.head == '<' =>
          org.json4s.Xml.toJson(XML.loadString(v))
        case v: String  => parse(v)
        case v: NodeSeq => org.json4s.Xml.toJson(v)
        case o          => parse(o.toJson)
      }
    }

    def toXml(wrap: Option[String] = None): String = {
      val xml = data match {
        case v: String if v.head == '<' => v
        case v: String =>
          org.json4s.Xml
            .toXml(v.toJValue)
            .toString()
        case o =>
          org.json4s.Xml
            .toXml(o.toJValue)
            .toString()
      }
      wrap match {
        case Some(value) => s"<${value}>${xml}</${value}>"
        case None        => xml
      }
    }

    def toXml: String = toXml(None)

    def logJson: String = {
      write(
        Map(
          "__" -> data.getClass.getName,
          "data" -> data
        )
      )
    }
  }

  implicit class JsonTo(data: String) {
    def jsonTo[T: Manifest]: T = {
      read[T](data)
    }
  }

  implicit class ReadXml(xml: String) {
    def xmlTo[T: Manifest]: T = {
      val json = xml.toJValue
      json.extract[T]
    }

    def childXmlTo[T: Manifest]: T = {
      val json = xml.toJValue
      json
        .extract[Map[String, Any]]
        .values
        .head
        .asInstanceOf[Map[String, Any]]
        .toJson
        .jsonTo[T]
    }
  }

  implicit class ParseXml(xml: NodeSeq) {
    def xmlTo[T: Manifest]: T = {
      val json = xml.toJValue
      json.extract[T]
    }

    def childXmlTo[T: Manifest]: T = {
      val json = xml.toJValue
      json
        .extract[Map[String, Any]]
        .values
        .head
        .asInstanceOf[Map[String, Any]]
        .toJson
        .jsonTo[T]
    }
  }

}
