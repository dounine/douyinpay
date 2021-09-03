package com.dounine.douyinpay.router.routers.errors

case class InvalidException(msg: String) extends Exception(msg)
