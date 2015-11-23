package io.generators.akka.cb

import java.util.concurrent.{TimeoutException, TimeUnit}

import akka.actor._
import akka.pattern.{CircuitBreakerOpenException, FutureTimeoutSupport, PipeToSupport}
import akka.testkit.{ImplicitSender, TestKit}
import io.generators.akka.cb.CircuitBreakerTester._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration


class CircuitSupportBreakerTest(_system: ActorSystem) extends TestKit(_system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("CircuitBreakerTest"))

  override def afterAll = {
    TestKit.shutdownActorSystem(system)
  }

  val supervisor = system.actorOf(Props(classOf[Supervisor], testActor))


  "CircuitBreakerSupport" must {

        "respond with OK message when Closed" in {
          supervisor ! Props[CircuitBreakerTester]
          val circuitBreaker = expectMsgType[ActorRef]

          circuitBreaker ! OkMessage(1)
          expectMsgAllOf(OkResponse(1))
        }


        "respond with Fail message when Closed" in {
          supervisor ! Props[CircuitBreakerTester]
          val circuitBreaker = expectMsgType[ActorRef]

          circuitBreaker ! FailMessage(1)
          expectMsgPF() {
            case FailResponse(e: IllegalArgumentException) =>
          }
        }

        "respond with Circuit breaker Open exception when tripped" in {
          supervisor ! Props[CircuitBreakerTester]
          val circuitBreaker = expectMsgType[ActorRef]

          circuitBreaker ! FailMessage(1)
          expectMsgPF() {
            case FailResponse(e: IllegalArgumentException) =>
          }

          circuitBreaker ! FailMessage(2)
          expectMsgPF() {
            case FailResponse(e: IllegalArgumentException) =>
          }

          circuitBreaker ! FailMessage(3)
          expectMsgPF() {
            case FailResponse(e: CircuitBreakerOpenException) =>
          }
        }

        "send stats for one circuit that transitioned several times" in {
          val supervisor = system.actorOf(Props(classOf[Supervisor], testActor))
          supervisor ! Props[CircuitBreakerTester]

          val circuitBreaker = expectMsgType[ActorRef]

          circuitBreaker ! OkMessage(1)
          circuitBreaker ! OkMessage(2)
          expectMsgAllOf(OkResponse(1), OkResponse(2))
          circuitBreaker ! FailMessage(3)
          expectMsgPF() {
            case FailResponse(e: IllegalArgumentException) =>
          }

          circuitBreaker ! FailMessage(4)
          expectMsgPF() {
            case FailResponse(e: IllegalArgumentException) =>
          }

          circuitBreaker ! FailMessage(5)
          expectMsgPF() {
            case FailResponse(e: CircuitBreakerOpenException) =>
          }
          circuitBreaker ! OkMessage(6)
          expectMsgPF() {
            case FailResponse(e: CircuitBreakerOpenException) =>
          }
          Thread.sleep(3000)
          circuitBreaker ! OkMessage(6)
          circuitBreaker ! OkMessage(7)
          expectMsgAllOf(OkResponse(6), OkResponse(7))
          circuitBreaker ! FailMessage(8)
          expectMsgPF() {
            case FailResponse(e: IllegalArgumentException) =>
          }

        }
  }
}

object CircuitBreakerTester {

  case class OkMessage(number: Int)

  case class FailMessage(number: Int)

  case class SlowMessage(waitDuration: FiniteDuration)

  case class OkResponse(number: Int)

  case class FailResponse(e: Throwable)

}

class CircuitBreakerTester extends CircuitBreakerSupport with Actor with FutureTimeoutSupport with PipeToSupport {
  override val maxFailures : Int = 3
  override val resetDuration: FiniteDuration = FiniteDuration(2, TimeUnit.SECONDS)
  override val timeoutDuration: FiniteDuration = FiniteDuration(1, TimeUnit.MILLISECONDS)
  implicit val exContext = context.dispatcher

  def receive: Receive = {
    case OkMessage(num) => sender ! OkResponse(num)
    case FailMessage(num) => throw new IllegalArgumentException("Failed")
    case SlowMessage(dur) =>log.warning("received slow")
      Thread.sleep(10000)
      log.warning("after sleep OK")
  }
}

class Supervisor(target: ActorRef) extends Actor with ActorLogging{

  import akka.actor.OneForOneStrategy
  import akka.actor.SupervisorStrategy._

  import scala.concurrent.duration._

  override val supervisorStrategy =
    OneForOneStrategy(maxNrOfRetries = 10, withinTimeRange = 1 minute) {
      case e: Throwable => target ! FailResponse(e)
        Resume
      case e =>
        log.warning("not handling exception")
        Resume
    }

  def receive = {
    case p: Props => sender() ! context.actorOf(p)
  }
}
