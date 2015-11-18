package io.generators.akka.cb

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import akka.actor.SupervisorStrategy.{Resume, Escalate}
import akka.actor.{OneForOneStrategy, SupervisorStrategy, Actor, ActorLogging}
import akka.pattern.CircuitBreakerOpenException

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal


trait CircuitBreakerSupport extends Actor with ActorLogging {

  val counter: AtomicLong = new AtomicLong(0)

  override def preStart = {
    log.info("Starting")
  }


  @throws[Exception](classOf[Exception])
  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    log.warning("Restaring because of: {}", reason.getMessage)
    super.preRestart(reason, message)
  }

  override def aroundReceive(receive: Receive, msg: Any): Unit = {
    log.error(s"Before receive...$msg")
    try {
      receive.applyOrElse(msg, unhandled)
    } catch {
      case NonFatal(e) => e match {
        case e: Throwable => if (counter.incrementAndGet() < 3) {
          log.error(s"Error ${counter.get()} in closed  $msg.")
          throw e
        } else {
          log.error(s"Error ${counter.get()} just before open  $msg.")
          context.become(circutOpenReceive)
          context.system.scheduler.scheduleOnce(FiniteDuration(1, TimeUnit.SECONDS)){context.become(circutHalfOpen)}(context.dispatcher)

          throw new CircuitBreakerOpenException(FiniteDuration(30, TimeUnit.SECONDS), "Broken")
        }
      }
    }

    log.error(s"After receive $msg.")
  }

  def circutOpenReceive: Receive = {
    case _ => throw new CircuitBreakerOpenException(FiniteDuration(30, TimeUnit.SECONDS), "Broken")
  }

  def circutHalfOpen: Receive = {
    case m => this.receive.apply(m)
  }
}
