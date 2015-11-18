package io.generators.akka.cb

import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.CircuitBreakerOpenException
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}


class CircuitBreakerTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("CircuitBreakerTest"))

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

  "CircuitBreakerDashboard actor" must {
    "send stats for one circuit that transitioned several times" in {
      val supervisor = system.actorOf(Props(classOf[Supervisor], testActor))
      supervisor ! Props[CircuitBreakerTester]

      val circuitBreaker = expectMsgType[ActorRef]

      circuitBreaker ! OkMessage(1)
      circuitBreaker ! OkMessage(2)
      expectMsgAllOf(OkResponse(1), OkResponse(2))
      circuitBreaker ! FailMessage(3)
      expectMsgPF() {
        case FailResponse(e: RuntimeException) =>
      }

      circuitBreaker ! FailMessage(4)
      expectMsgPF() {
        case FailResponse(e: RuntimeException) =>
      }

      circuitBreaker ! FailMessage(5)
      expectMsgPF() {
        case FailResponse(e: CircuitBreakerOpenException) =>
      }
      circuitBreaker ! OkMessage(6)
      expectMsgPF() {
        case FailResponse(e: CircuitBreakerOpenException) =>
      }
      Thread.sleep(2000)
      circuitBreaker ! OkMessage(6)
      circuitBreaker ! OkMessage(7)
      expectMsgAllOf(OkResponse(6), OkResponse(7))
    }
  }
}

case class OkMessage(number: Int)

case class FailMessage(number: Int)

case class OkResponse(number: Int)

case class FailResponse(e: Throwable)

class CircuitBreakerTester extends CircuitBreakerSupport with Actor {
  def receive: Receive = {
    case OkMessage(num) => sender ! OkResponse(num)
    case FailMessage(num) => throw new RuntimeException("Abc")
  }
}

class Supervisor(target: ActorRef) extends Actor {

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._

  import scala.concurrent.duration._

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case e: CircuitBreakerOpenException => target ! FailResponse(e)
        Resume
      case e: RuntimeException => target ! FailResponse(e)
        Resume
    }

  def receive = {
    case p: Props => sender() ! context.actorOf(p)
  }
}
