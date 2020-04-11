package org.http4s

import cats.effect.IO

object DecodeResult {
  def success[A](fa: IO[A]): DecodeResult[A] =
    fa.map(Right(_))

  def success[A](a: A): DecodeResult[A] =
    IO.pure(Right(a))

  def failure[A](fe: IO[DecodeFailure]): DecodeResult[A] =
    fe.map(Left(_))

  def failure[A](e: DecodeFailure): DecodeResult[A] =
    IO.pure(Left(e))
}
