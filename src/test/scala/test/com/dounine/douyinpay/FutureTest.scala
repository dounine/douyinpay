package test.com.dounine.douyinpay

import java.util.concurrent.TimeUnit
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
object FutureTest {

  def main(args: Array[String]): Unit = {
    System.setProperty("scala.concurrent.context.minThreads", "16")
    System.setProperty("scala.concurrent.context.maxThreads", "16")
    (1 to 200).foreach(i =>
      Future {
        println(Thread.currentThread().getName + " ->" + i)
        TimeUnit.SECONDS.sleep(1)
      }
    )

    println(Runtime.getRuntime.availableProcessors())

    TimeUnit.SECONDS.sleep(2)
  }

}
