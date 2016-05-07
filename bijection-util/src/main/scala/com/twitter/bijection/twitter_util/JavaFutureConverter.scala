/*
Copyright 2012 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.bijection.twitter_util

import java.util.concurrent.{ Future => JFuture }

import com.twitter.util._

/**
 * Base class for converting java futures to twitter futures
 */
abstract class JavaFutureConverter {
  def apply[T](javaFuture: JFuture[T]): Future[T]
}

/**
 * Converter based on a [[FuturePool]] which will create one thread per future.
 * To favor if there aren't too many futures to convert and one cares about latency.
 * @param mayInterruptIfRunning whether or not the initial java future can be interrupted if it's
 *                              running
 */
class FuturePoolJavaFutureConverter(mayInterruptIfRunning: Boolean) extends JavaFutureConverter {
  override def apply[T](javaFuture: JFuture[T]): Future[T] = {
    val f = FuturePool.unboundedPool { javaFuture.get() }
    val p = Promise.attached(f)
    p.setInterruptHandler { case NonFatal(e) =>
      if (p.detach()) {
        f.raise(e)
        javaFuture.cancel(mayInterruptIfRunning)
      }
    }
    p
  }
}

/**
 * Converter based on a [[Timer]] which will create a task which will check every
 * <code>checkFrequency</code> if the java future is completed, one thread will be used for every
 * conversion.
 * To favor if there are a lot of futures to convert and one cares less about the latency induced
 * by <code>checkFrequency</code>.
 * @param timer timer used to schedule a task which will check if the java future is done
 * @param checkFrequency frequency at which the java future will be checked for completion
 * @param mayInterruptIfRunning whether or not the initial java future can be interrupted if it's
 *                              running
 */
class TimerJavaFutureConverter(
  timer: Timer,
  checkFrequency: Duration,
  mayInterruptIfRunning: Boolean
) extends JavaFutureConverter {
  override def apply[T](javaFuture: JFuture[T]): Future[T] = {
    val p = Promise[T]
    val task = timer.schedule(checkFrequency) {
      if (javaFuture.isDone) {
        p.updateIfEmpty(Try(javaFuture.get()))
      }
    }
    p.setInterruptHandler { case NonFatal(e) =>
      task.cancel()
      p.updateIfEmpty(Throw(e))
      javaFuture.cancel(mayInterruptIfRunning)
    }
    p
  }
}

