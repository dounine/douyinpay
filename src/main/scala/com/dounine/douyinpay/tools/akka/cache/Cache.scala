package com.dounine.douyinpay.tools.akka.cache

import akka.actor.typed.{ActorRef, ActorSystem}
import akka.stream.SystemMaterializer
import akka.stream.scaladsl.{Sink, Source}
import akka.stream.typed.scaladsl.{ActorFlow, ActorSource}
import akka.util.Timeout
import com.dounine.douyinpay.behaviors.cache.ReplicatedCacheBehavior
import org.json4s.Formats

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

class Cache(system: ActorSystem[_]) {

  private val replicatedCacheBehavior =
    system.systemActorOf(ReplicatedCacheBehavior(), "ReplicatedCacheBehavior")
  private val materializer = SystemMaterializer(system).materializer
  private implicit val timeout = Timeout(3.seconds)

  def get[T: Manifest](
      key: String
  )(implicit formats: Formats): Future[Option[T]] = {
    Source
      .single(key)
      .via(
        ActorFlow.ask(replicatedCacheBehavior)(
          (k: String, replyTo: ActorRef[ReplicatedCacheBehavior.Cached]) =>
            ReplicatedCacheBehavior.GetCache(k)(replyTo)
        )
      )
      .map(_.value.map(_.asInstanceOf[T]))
      .runWith(Sink.head)(materializer)
  }

  def orElse[T: Manifest](
      key: String,
      ttl: FiniteDuration,
      value: () => Future[T]
  )(implicit formats: Formats): Future[T] = {
    Source
      .single(key)
      .via(
        ActorFlow.ask(replicatedCacheBehavior)(
          (k: String, replyTo: ActorRef[ReplicatedCacheBehavior.Cached]) =>
            ReplicatedCacheBehavior.GetCache(k)(replyTo)
        )
      )
      .flatMapConcat { cached =>
        cached.value match {
          case Some(_) => Source.single(cached)
          case None =>
            Source
              .future(
                value()
              )
              .via(
                ActorFlow.ask(replicatedCacheBehavior)(
                  (v: T, replyTo: ActorRef[ReplicatedCacheBehavior.Cached]) =>
                    ReplicatedCacheBehavior.PutCache(key, v, ttl)(replyTo)
                )
              )
        }
      }
      .map(_.value.get.asInstanceOf[T])
      .recover {
        case e: Throwable => {
          e.printStackTrace()
          throw e
        }
      }
      .runWith(Sink.head)(materializer)
  }

  def put[T: Manifest](
      key: String,
      value: T,
      ttl: FiniteDuration
  ): Future[T] = {
    Source
      .single(key)
      .via(
        ActorFlow.ask(replicatedCacheBehavior)(
          (k: String, replyTo: ActorRef[ReplicatedCacheBehavior.Cached]) =>
            ReplicatedCacheBehavior.PutCache(k, value, ttl)(replyTo)
        )
      )
      .map(_.value.get.asInstanceOf[T])
      .runWith(Sink.head)(materializer)
  }

  def remove(
      key: String
  ): Future[Boolean] = {
    Source
      .single(key)
      .via(
        ActorFlow.ask(replicatedCacheBehavior)(
          (k: String, replyTo: ActorRef[ReplicatedCacheBehavior.Deleted]) =>
            ReplicatedCacheBehavior.DeleteCache(k)(replyTo)
        )
      )
      .map(_.failre)
      .recover {
        case e => false
      }
      .runWith(Sink.head)(materializer)
  }

}
