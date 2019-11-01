package org.http4s
package client

import fs2.Stream
import fs2.async.mutable.Semaphore
import cats.effect._
import cats.implicits._

object SemaphoreUtil {
  def withPermit[F[_]: Concurrent, A](sem: Semaphore[F])(t: F[A]): F[A] =
    Stream.bracket(sem.decrementBy(1))(
      _ => Stream.eval(t),
      _ => sem.incrementBy(1)
    ).compile.last.map(_.get)


}