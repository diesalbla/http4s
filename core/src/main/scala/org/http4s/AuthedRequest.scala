package org.http4s

import cats.effect.IO

object AuthedRequest {
  def apply[T](getUser: Request => IO[T]): Request => IO[AuthedRequest[T]] =
    ContextRequest[T](getUser)

  def apply[T](context: T, req: Request): AuthedRequest[T] =
    ContextRequest[T](context, req)
}
