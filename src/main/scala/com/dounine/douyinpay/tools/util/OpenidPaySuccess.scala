package com.dounine.douyinpay.tools.util

import org.slf4j.LoggerFactory

import scala.collection.mutable

object OpenidPaySuccess {

  private val logger = LoggerFactory.getLogger(OpenidPaySuccess.getClass)
  private val successOrders: mutable.Map[String, (Int, Int)] =
    mutable.Map[String, (Int, Int)]()

  def add(openid: String, money: Int): Unit = {
    successOrders.get(openid) match {
      case Some((successCount: Int, successMoney: Int)) =>
        successOrders += openid -> (successCount + 1, successMoney + money)
      case None =>
        successOrders += openid -> (1, money)
    }
  }

  def init(openids: Map[String, (Int, Int)]): Unit = {
    logger.info("init pay success openids -> {}", openids.size)
    successOrders ++= openids
  }

  def query(openid: String): {
    val count: Int
    val money: Int
  } = {
    val info = successOrders.getOrElse(openid, (0, 0))
    new {
      val count: Int = info._1
      val money: Int = info._2
    }
  }

}
