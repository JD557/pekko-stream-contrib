/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package org.apache.pekko.stream.contrib

import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.scaladsl._
import org.apache.pekko.stream.testkit.scaladsl.{ TestSink, TestSource }

import scala.concurrent.duration._
import scala.util.{ Failure, Success, Try }

class RetrySpec extends BaseStreamSpec {

  val failedElem: Try[Int] = Failure(new Exception("cooked failure"))
  def flow[T] = Flow.fromFunction[(Int, T), (Try[Int], T)] {
    case (i, j) if i % 2 == 0 => (failedElem, j)
    case (i, j)               => (Success(i + 1), j)
  }

  "Retry" should {
    "retry ints according to their parity" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(Retry(flow[Int]) { s =>
          if (s < 42) Some((s + 1, s + 1))
          else None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(4))
      source.sendNext(42)
      assert(sink.expectNext()._1 === failedElem)
      source.sendComplete()
      sink.expectComplete()
    }

    "retry descending ints until success" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, (i to 0 by -2).toList ::: List(i + 1)))
        .via(Retry(flow[List[Int]]) {
          case x :: xs => Some(x -> xs)
          case Nil     => throw new IllegalStateException("should not happen")
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(4))
      source.sendNext(40)
      assert(sink.expectNext()._1 === Success(42))
      source.sendComplete()
      sink.expectComplete()
    }

    "retry squares by division" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i * i))
        .via(Retry(flow[Int]) {
          case x if x % 4 == 0 => Some((x / 2, x / 4))
          case x => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some((sqrt, x))
          }
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(4)
      assert(sink.expectNext()._1 === Success(2))
      sink.expectNoMessage(3.seconds)
      source.sendComplete()
      sink.expectComplete()
    }

    "tolerate killswitch terminations after start" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.both)
        .map(i => (i, i * i))
        .via(Retry(flow[Int]) {
          case x if x % 4 == 0 => Some((x / 2, x / 4))
          case x => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some((sqrt, x))
          }
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations on start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(Retry(flow[Int]) { x =>
          Some((x, x + 1))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations before start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(Retry(flow[Int]) { x =>
          Some((x, x + 1))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(failedElem.failed.get)
      sink.request(1)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow after start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i * i))
        .viaMat(Retry(innerFlow) {
          case x if x % 4 == 0 => Some((x / 2, x / 4))
          case x => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some((sqrt, x))
          }
        })(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow on start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(Retry(innerFlow) { x =>
          Some((x, x + 1))
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow before start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(Retry(innerFlow) { x =>
          Some((x, x + 1))
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(failedElem.failed.get)
      sink.request(1)
      sink.expectError(failedElem.failed.get)
    }

    val alwaysFailingFlow = Flow.fromFunction[(Int, Int), (Try[Int], Int)] {
      case (i, j) => (failedElem, j)
    }

    val alwaysRecoveringFunc: Int => Option[(Int, Int)] = i => Some(i -> i)

    val stuckForeverRetrying = Retry(alwaysFailingFlow)(alwaysRecoveringFunc)

    "tolerate killswitch terminations before the flow while on fail spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.both)
        .map(i => (i, i))
        .via(stuckForeverRetrying)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      sink.expectNoMessage()
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow while on fail spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(
          Retry(alwaysFailingFlow.viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right))(alwaysRecoveringFunc))(
          Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      sink.expectNoMessage()
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations after the flow while on fail spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(stuckForeverRetrying)
        .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      sink.expectNoMessage()
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }
  }

  "RetryConcat" should {
    "swallow failed elements that are retried with an empty seq" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(Retry.concat(100, 100, flow[Int]) { _ =>
          Some(Nil)
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      sink.expectNoMessage()
      source.sendNext(3)
      assert(sink.expectNext()._1 === Success(4))
      source.sendNext(4)
      sink.expectNoMessage()
      source.sendComplete()
      sink.expectComplete()
    }

    "concat incremented ints and modulo 3 incremented ints from retries" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(Retry.concat(100, 100, flow[Int]) { os =>
          val s = (os + 1) % 3
          if (os < 42) Some(List((os + 1, os + 1), (s, s)))
          else if (os == 42) Some(Nil)
          else None
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(4))
      assert(sink.expectNext()._1 === Success(2))
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(44)
      assert(sink.expectNext()._1 === failedElem)
      source.sendNext(42)
      sink.expectNoMessage()
      source.sendComplete()
      sink.expectComplete()
    }

    "retry squares by division" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i * i))
        .via(Retry.concat(100, 100, flow[Int]) {
          case x if x % 4 == 0 => Some(List((x / 2, x / 4)))
          case x => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some(List((sqrt, x)))
          }
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(4)
      assert(sink.expectNext()._1 === Success(2))
      sink.expectNoMessage(3.seconds)
      source.sendComplete()
      sink.expectComplete()
    }

    "tolerate killswitch terminations after start" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.both)
        .map(i => (i, i * i))
        .via(Retry.concat(100, 100, flow[Int]) {
          case x if x % 4 == 0 => Some(List((x / 2, x / 4)))
          case x => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some(List((sqrt, x)))
          }
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations on start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(Retry.concat(100, 100, flow[Int]) { x =>
          Some(List((x, x + 1)))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations before start" in {
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.right)
        .map(i => (i, i))
        .via(Retry.concat(100, 100, flow[Int]) { x =>
          Some(List((x, x + 1)))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(failedElem.failed.get)
      sink.request(1)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow after start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i * i))
        .viaMat(Retry.concat(100, 100, innerFlow) {
          case x if x % 4 == 0 => Some(List((x / 2, x / 4)))
          case x => {
            val sqrt = scala.math.sqrt(x.toDouble).toInt
            Some(List((sqrt, x)))
          }
        })(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 === Success(2))
      source.sendNext(2)
      assert(sink.expectNext()._1 === Success(2))
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow on start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(Retry.concat(100, 100, innerFlow) { x =>
          Some(List((x, x + 1)))
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow before start" in {
      val innerFlow = flow[Int].viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right)
      val (killSwitch, sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(Retry.concat(100, 100, innerFlow) { x =>
          Some(List((x, x + 1)))
        })(Keep.right)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      killSwitch.abort(failedElem.failed.get)
      sink.request(1)
      sink.expectError(failedElem.failed.get)
    }

    val alwaysFailingFlow = Flow.fromFunction[(Int, Int), (Try[Int], Int)] {
      case (i, j) => (failedElem, j)
    }

    val alwaysRecoveringFunc: Int => Option[List[(Int, Int)]] = i => Some(List(i -> i))

    val stuckForeverRetrying = Retry.concat(Long.MaxValue, Long.MaxValue, alwaysFailingFlow)(alwaysRecoveringFunc)

    "tolerate killswitch terminations before the flow while on fail spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .viaMat(KillSwitches.single[Int])(Keep.both)
        .map(i => (i, i))
        .via(stuckForeverRetrying)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      sink.expectNoMessage()
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations inside the flow while on fail spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .viaMat(
          Retry.concat(Long.MaxValue,
            Long.MaxValue,
            alwaysFailingFlow.viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.right))(alwaysRecoveringFunc))(
          Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      sink.expectNoMessage()
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "tolerate killswitch terminations after the flow while on fail spin" in {
      val ((source, killSwitch), sink) = TestSource
        .probe[Int]
        .map(i => (i, i))
        .via(stuckForeverRetrying)
        .viaMat(KillSwitches.single[(Try[Int], Int)])(Keep.both)
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      sink.expectNoMessage()
      killSwitch.abort(failedElem.failed.get)
      sink.expectError(failedElem.failed.get)
    }

    "finish only after processing all elements in stream" in {
      val (source, sink) = TestSource
        .probe[Int]
        .map(i => (i, i * i))
        .via(Retry.concat(100, 100, flow[Int]) {
          case x => Some(List.fill(x)(1 -> 1))
        })
        .toMat(TestSink.probe)(Keep.both)
        .run()

      sink.request(99)
      source.sendNext(1)
      assert(sink.expectNext()._1 == Success(2))

      source.sendNext(3)
      assert(sink.expectNext()._1 == Success(4))

      source.sendNext(2)
      source.sendComplete()
      assert(sink.expectNext()._1 == Success(2))
      assert(sink.expectNext()._1 == Success(2))
      assert(sink.expectNext()._1 == Success(2))
      assert(sink.expectNext()._1 == Success(2))

      sink.expectComplete()
    }
  }
}
