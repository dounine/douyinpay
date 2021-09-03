package com.dounine.douyinpay.tools.util

import org.slf4j.LoggerFactory

import scala.collection.mutable

object LockedUsers {

  private val logger = LoggerFactory.getLogger(LockedUsers.getClass)
  private val lockedUsers =
    mutable.Set[String]()

  def init(openids: Set[String]): Unit = {
    logger.info("locked users openids -> {}", openids.size)
    lockedUsers ++= openids
  }

  def isLocked(openid: String): Boolean = {
    lockedUsers.contains(openid)
  }

}
