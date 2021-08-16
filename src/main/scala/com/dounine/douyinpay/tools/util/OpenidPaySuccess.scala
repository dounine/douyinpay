package com.dounine.douyinpay.tools.util

import org.slf4j.LoggerFactory

import scala.collection.mutable

object OpenidPaySuccess {

  private val logger = LoggerFactory.getLogger(OpenidPaySuccess.getClass)
  private val successOrders: mutable.Map[String, Int] =
    mutable.Map[String, Int]()

  def add(openid: String): Int = {
    successOrders.get(openid) match {
      case Some(successCount: Int) =>
        successOrders += openid -> (successCount + 1)
        openid -> (successCount + 1)
        successCount + 1
      case None =>
        successOrders += openid -> 1
        1
    }
  }

  def init(openids: Map[String, Int]): Unit = {
    logger.info("init pay success openids -> {}", openids.size)
    successOrders ++= openids
  }

  def query(openid: String): Int = {
    successOrders.getOrElse(openid, 0)
  }

}
