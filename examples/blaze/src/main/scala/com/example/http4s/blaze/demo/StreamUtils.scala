package com.example.http4s.blaze.demo

import cats.effect.Sync
import fs2.Stream

trait StreamUtils[F[_]] {
  def evalF[A](thunk: => A)(implicit F: Sync[F]): Stream[IO, A] = Stream.eval(F.delay(thunk))
  def putStrLn(value: String)(implicit F: Sync[F]): Stream[IO, Unit] = evalF(println(value))
  def putStr(value: String)(implicit F: Sync[F]): Stream[IO, Unit] = evalF(print(value))
  def env(name: String)(implicit F: Sync[F]): Stream[IO, Option[String]] = evalF(sys.env.get(name))
  def error(msg: String)(implicit F: Sync[F]): Stream[IO, String] =
    Stream.raiseError(new Exception(msg)).covary[F]
}

object StreamUtils {
  implicit def syncInstance[F[_]: Sync]: StreamUtils[F] = new StreamUtils[F] {}
}
