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
  val halfOpenDuration: FiniteDuration = FiniteDuration(1, TimeUnit.MINUTES)

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    try {
      receive.applyOrElse(msg, unhandled)
    } catch {
      case NonFatal(e) => e match {
        case e: Throwable => if (counter.incrementAndGet() < maxFailures) {
          throw e
        } else {
          context.become(circutOpenReceive)
          context.system.scheduler.scheduleOnce(halfOpenDuration){context.become(circutHalfOpen)}(context.dispatcher)
          notifyBroken
        }
      }
    }
  }

  def notifyBroken: Nothing = {
    throw new CircuitBreakerOpenException(halfOpenDuration)
  }

  def circutOpenReceive: Receive = {
    case _ => notifyBroken
  }

  def circutHalfOpen: Receive = {
    case m => this.receive.apply(m)
  }
}
