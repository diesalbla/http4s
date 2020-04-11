package org.http4s
package syntax

import cats.effect.IO

trait KleisliSyntax {
  implicit def http4sKleisliResponseSyntaxOptionT[A](kleisli: A => IO[Option[Response]]): KleisliResponseOps[A] =
    new KleisliResponseOps[A](kleisli)
}

final class KleisliResponseOps[A](self: A => IO[Option[Response]]) {
  def orNotFound: A => IO[Response] =
    a => self(a).map(_.getOrElse(Response.notFound))
}
