package com.dounine.douyinpay.router.routers

import akka.actor.typed.ActorSystem
import akka.cluster.{Cluster, MemberStatus}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import com.dounine.douyinpay.tools.util.IpUtils

import scala.concurrent.duration._

class HealthRouter()(implicit system: ActorSystem[_]) extends SuportRouter {
  val cluster: Cluster = Cluster.get(system)

  val route =
    cors() {
      concat(get {
        path("ip") {
          extractClientIP { ip =>
            {
              val oip = ip.getIp()
              val (province, city) =
                IpUtils.convertIpToProvinceCity(oip)
              ok(
                Map(
                  "ip" -> oip,
                  "city" -> city,
                  "province" -> province
                )
              )
            }
          }
        } ~ path("health") {
          ok
        } ~ path("ready") {
          withRequestTimeout(1.seconds, request => timeoutResponse) {
            ok
          }
        } ~ path("alive") {
          if (
            cluster.selfMember.status == MemberStatus.Up || cluster.selfMember.status == MemberStatus.WeaklyUp
          ) {
            ok
          } else {
            complete(StatusCodes.NotFound)
          }
        }
      })
    }
}
