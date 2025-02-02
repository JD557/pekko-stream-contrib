/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package org.apache.pekko.stream.contrib

import org.apache.pekko.NotUsed
import org.apache.pekko.stream.scaladsl.{ Flow, Keep, Source }
import org.apache.pekko.stream.testkit.TestSubscriber
import org.apache.pekko.stream.testkit.scaladsl.TestSink

class PassThroughFlowSpec extends BaseStreamSpec {

  "a stream via PassThroughFlow" should {
    "pass input elements alongside output" in {
      val originalFlow: Flow[Int, String, NotUsed] = Flow[Int].map(_.toString)

      val probe: TestSubscriber.Probe[(Int, String)] = Source(1 to 10)
        .via(PassThroughFlow(originalFlow))
        .toMat(TestSink.probe)(Keep.right)
        .run()

      probe
        .request(10)
        .expectNextN((1 to 10).map(i => i -> i.toString))
    }

    "apply a function to the output" in {
      val sideEffects = List.newBuilder[String]

      val originalFlow: Flow[Int, sideEffects.type, NotUsed] =
        Flow[Int].map(i => sideEffects += i.toString)

      val probe: TestSubscriber.Probe[Int] = Source(1 to 10)
        .via(PassThroughFlow(originalFlow, Keep.left))
        .toMat(TestSink.probe)(Keep.right)
        .run()

      probe
        .request(10)
        .expectNextN(1 to 10)

      assert(sideEffects.result() == (1 to 10).map(_.toString))
    }

    "combine elements given a function" in {
      val originalFlow: Flow[Int, Int, NotUsed] = Flow[Int].map(i => i * i)

      val probe: TestSubscriber.Probe[Int] = Source(1 to 10)
        .via(PassThroughFlow(originalFlow, (i: Int, o: Int) => o / i))
        .toMat(TestSink.probe)(Keep.right)
        .run()

      probe
        .request(10)
        .expectNextN(1 to 10)
    }
  }
}
