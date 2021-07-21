package com.dounine.douyinpay.service

import akka.NotUsed
import akka.actor.typed.ActorSystem
import akka.cluster.sharding.typed.scaladsl.ClusterSharding
import akka.stream.FlowShape
import akka.stream.alpakka.slick.scaladsl.{Slick, SlickSession}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, ZipWith}
import com.dounine.douyinpay.behaviors.engine.CoreEngine
import com.dounine.douyinpay.model.models.{OrderModel, UserModel}
import com.dounine.douyinpay.store.{OrderTable, UserTable}
import com.dounine.douyinpay.tools.akka.db.DataSource

import scala.concurrent.ExecutionContextExecutor

object OrderStream {

}
