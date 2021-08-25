package com.dounine.douyinpay.router.routers.errors

case class AuthException(msg: String) extends Exception(msg)
