package io.generators.akka.cb

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import akka.actor.{Actor, ActorLogging}
import akka.pattern.CircuitBreakerOpenException

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal


trait CircuitBreakerSupport extends Actor with ActorLogging {
  val counter: AtomicLong = new AtomicLong(0)
  val maxFailures: Int = 5
  val resetDuration: FiniteDuration = FiniteDuration(1, TimeUnit.MINUTES)
  val timeoutDuration: FiniteDuration = FiniteDuration(10, TimeUnit.SECONDS)

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    try {
      receive.applyOrElse(msg, unhandled)
      counter.set(0)
    } catch {
      case NonFatal(e) => e match {
        case e: Throwable => if (counter.incrementAndGet() < maxFailures) {
          throw e
        } else {
          context.become(circutOpenReceive)
          context.system.scheduler.scheduleOnce(resetDuration){context.become(circutHalfOpen)}(context.dispatcher)
          notifyBroken
        }
      }
    }
  }

  def notifyBroken: Nothing = {
    throw new CircuitBreakerOpenException(resetDuration)
  }

  def circutOpenReceive: Receive = {
    case _ => notifyBroken
  }

  def circutHalfOpen: Receive = {
    case m => this.receive.apply(m)
  }
}
