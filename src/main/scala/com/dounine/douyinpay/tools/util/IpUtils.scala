package com.dounine.douyinpay.tools.util

import org.apache.commons.codec.binary.StringUtils

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.io.Source

object IpUtils {
  case class Ip2City(
      start_ip: Long,
      end_ip: Long,
      province: String,
      city: String
  )
  private val UNKNOWN = "unknown"
  private lazy val (startIpArray, ip2CityMap) = loadIp2City
  private val ip2CityPattern =
    """\((?:\d{1,5}),'?(\d{1,11})'?,'?(\d{1,11})'?,'(.+?)','(.*?)'\)[,;]""".r
  private val ipPattern = """(\d{1,3})\.(\d{1,3})\.(\d{1,3})\.(\d{1,3})""".r

  def convertIpToProvinceCity(ip: String): (String, String) = {
    val ipLong = convertIpToLong(ip)
    val searchIndex = java.util.Arrays.binarySearch(startIpArray, ipLong)
    val realIndex = if (searchIndex < 0) searchIndex.abs - 2 else searchIndex
    val realIp2City = ip2CityMap.get(startIpArray(realIndex)) match {
      case Some(ip2City) =>
        if (ipLong >= ip2City.start_ip && ipLong <= ip2City.end_ip) ip2City
        else null
      case _ => null
    }
    if (realIp2City != null) {
      val city = if (realIp2City.city == null || realIp2City.city == "") {
        realIp2City.province
      } else realIp2City.city
      (realIp2City.province, city)
    } else (UNKNOWN, UNKNOWN)
  }

  private def convertIpToLong(ip: String): Long =
    ip match {
      case ipPattern(one, two, three, four) =>
        one.toLong << 24 | two.toLong << 16 | three.toLong << 8 | four.toLong
      case _ => 0L
    }

  private val ipCitys = Source
    .fromInputStream(
      IpUtils.getClass.getResourceAsStream("/ip2city.sql")
    )
    .getLines()
    .toList

  private def loadIp2City(): (Array[Long], Map[Long, Ip2City]) = {
    val ipArray = new ListBuffer[Long]()
    val ip2CityMap = mutable.Map[Long, Ip2City]()
    for (line <- ipCitys) line match {
      case ip2CityPattern(start_ip, end_ip, province, city) =>
        ipArray += start_ip.toLong
        ip2CityMap += start_ip.toLong -> Ip2City(
          start_ip.toLong,
          end_ip.toLong,
          province,
          city
        )
      case _ =>
    }
    val resultArray = ipArray.reverse.sorted.toArray
    (resultArray, ip2CityMap.toMap)
  }

}
