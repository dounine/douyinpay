package com.dounine.douyinpay.behaviors.cache

import akka.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import akka.actor.typed.{ActorRef, Behavior}
import akka.cluster.ddata.typed.scaladsl.Replicator._
import akka.cluster.ddata.typed.scaladsl.{
  DistributedData,
  Replicator,
  ReplicatorMessageAdapter
}
import akka.cluster.ddata.{LWWMap, LWWMapKey, SelfUniqueAddress}
import com.dounine.douyinpay.model.models.BaseSerializer
import org.slf4j.LoggerFactory

import scala.concurrent.duration.FiniteDuration

object ReplicatedCacheBehavior {

  private val logger = LoggerFactory.getLogger(ReplicatedCacheBehavior.getClass)
  sealed trait Command extends BaseSerializer

  final case class PutCache(key: String, value: Any, timeout: FiniteDuration)(
      val replyTo: ActorRef[Cached]
  ) extends Command

  final case class GetCache(key: String)(val replyTo: ActorRef[Cached])
      extends Command

  final case class Cached(key: String, value: Option[Any]) extends Command

  final case class Deleted(key: String, failre: Boolean) extends Command

  final case class DeleteCache(key: String)(val replyTo: ActorRef[Deleted])
      extends Command

  private sealed trait InternalCommand extends Command

  private case class InternalGetResponse(
      key: String,
      rsp: GetResponse[LWWMap[String, Any]]
  )(val replyTo: ActorRef[Cached])
      extends InternalCommand

  private case class InternalUpdateResponse(
      req: PutCache,
      rsp: UpdateResponse[LWWMap[String, Any]]
  ) extends Command

  private case class InternalDeleteResponse(
      req: DeleteCache,
      rsp: UpdateResponse[LWWMap[String, Any]]
  ) extends Command

  def apply(): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.withTimers((timers: TimerScheduler[Command]) => {
        DistributedData
          .withReplicatorMessageAdapter[Command, LWWMap[String, Any]] {
            replicator: ReplicatorMessageAdapter[
              Command,
              LWWMap[String, Any]
            ] =>
              implicit val node: SelfUniqueAddress =
                DistributedData(context.system).selfUniqueAddress

              def dataKey(entryKey: String): LWWMapKey[String, Any] =
                LWWMapKey("cache-" + math.abs(entryKey.hashCode % 100))

              Behaviors.receiveMessage[Command] {
                case e @ Deleted(_, _) =>
                  logger.info("cache deleted -> {}", e)
                  Behaviors.same
                case Cached(_, _) =>
                  Behaviors.same
                case e @ PutCache(key, value, timeout) =>
                  replicator.askUpdate(
                    createRequest = Update(
                      key = dataKey(key),
                      initial = LWWMap.empty[String, Any],
                      writeConsistency = Replicator.WriteLocal
                    ) {
                      (_: LWWMap[String, Any]) :+ (key -> value)
                    },
                    responseAdapter = rsp => InternalUpdateResponse(e, rsp)
                  )
                  timers.startSingleTimer(
                    key = "cache-ttl-" + key,
                    msg = DeleteCache(key)(context.self),
                    delay = timeout
                  )
                  Behaviors.same
                case e @ DeleteCache(key) =>
                  timers.cancel("cache-ttl-" + key)
                  replicator.askUpdate(
                    createRequest = Update(
                      key = dataKey(key),
                      initial = LWWMap.empty[String, Any],
                      writeConsistency = Replicator.WriteLocal
                    ) {
                      _.remove(node, key)
                    },
                    responseAdapter = rsp => InternalDeleteResponse(e, rsp)
                  )
                  Behaviors.same
                case e @ GetCache(key: String) =>
                  replicator.askGet(
                    createRequest = Get(dataKey(key), Replicator.ReadLocal),
                    responseAdapter =
                      rsp => InternalGetResponse(key, rsp)(e.replyTo)
                  )
                  Behaviors.same

                case e @ InternalGetResponse(key, g @ GetSuccess(_)) =>
                  e.replyTo.tell(Cached(key, g.dataValue.get(key)))
                  Behaviors.same
                case e @ InternalGetResponse(key, g @ NotFound(_)) =>
                  e.replyTo.tell(Cached(key, None))
                  Behaviors.same
                case e @ InternalGetResponse(key, g @ GetDataDeleted(_)) =>
                  e.replyTo.tell(Cached(key, None))
                  Behaviors.same
                case e @ InternalUpdateResponse(req, g @ UpdateSuccess(rsp)) =>
                  req.replyTo.tell(
                    Cached(req.key, Some(req.value))
                  )
                  Behaviors.same
                case e @ InternalUpdateResponse(
                      req,
                      g @ UpdateDataDeleted(rsp)
                    ) =>
                  req.replyTo.tell(
                    Cached(req.key, Some(req.value))
                  )
                  Behaviors.same
                case InternalDeleteResponse(req, g @ UpdateSuccess(rsp)) =>
                  req.replyTo.tell(
                    Deleted(req.key, failre = false)
                  )
                  Behaviors.same
                case InternalDeleteResponse(req, g @ UpdateFailure(rsp)) =>
                  req.replyTo.tell(
                    Deleted(req.key, failre = true)
                  )
                  Behaviors.same
                case e: InternalGetResponse => {
                  logger.error("get error -> {}", e)
                  Behaviors.same
                }
                case e: InternalUpdateResponse => {
                  logger.error("update error -> {}", e)
                  Behaviors.same
                }
                case e: InternalDeleteResponse => {
                  logger.error("delete error -> {}", e)
                  Behaviors.same
                }
              }

          }
      })
    }

}
