package org.http4s

import cats.implicits._
import cats.effect.IO

/**
  * The type parameters need to be in this order to make partial unification
  * trigger. See https://github.com/http4s/http4s/issues/1506
  */
trait ContextRoutes[T] {
  def apply(authReq: ContextRequest[T]): IO[Option[Response]]
}

object ContextRoutes {

  /** Lifts a function into an [[ContextRoutes]].  The application of `run`
    * is suspended in `F` to permit more efficient combination of
    * routes via `SemigroupK`.
    *
    * @tparam T the type of the auth info in the [[ContextRequest]] accepted by the [[ContextRoutes]]
    * @param run the function to lift
    * @return an [[ContextRoutes]] that wraps `run`
    */
  def apply[T](run: ContextRequest[T] => IO[Option[Response]]): ContextRoutes[T] =
    req => IO.suspend(run(req))

  /** Lifts a partial function into an [[ContextRoutes]].  The application of the
    * partial function is suspended in `F` to permit more efficient combination
    * of authed services via `SemigroupK`.
    *
    * @param pf the partial function to lift
    * @return An [[ContextRoutes]] that returns some [[Response]] in an `IO[Option[*]]`
    * wherever `pf` is defined, an `OptionT.none` wherever it is not
    */
  def of[T](pf: PartialFunction[ContextRequest[T], IO[Response]]): ContextRoutes[T] =
    req => IO.suspend(pf.lift(req).sequence)

  /** Lifts a partial function into an [[ContextRoutes]].  The application of the
    * partial function is not suspended in `F`, unlike [[of]]. This allows for less
    * constraints when not combining many routes.
    *
    * @param pf the partial function to lift
    * @return A [[ContextRoutes]] that returns some [[Response]] in a `Some` in an `IO`
    * wherever `pf` is defined, and an  `IO(None)` wherever it is not
    */
  def strict[T](pf: PartialFunction[ContextRequest[T], IO[Response]]): ContextRoutes[T] =
    req => pf.lift(req).sequence

  /**
    * The empty service (all requests fallthrough).
    *
    * @tparam T - ignored.
    * @return
    */
  def empty[T]: ContextRoutes[T] = _ => IO.pure(None)
}
