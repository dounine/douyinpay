package com.dounine.douyinpay.router.routers.errors

case class LockedException(msg: String) extends Exception(msg)
