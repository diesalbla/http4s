package org.http4s

import cats.effect.IO
import cats.implicits._

/** A function of a [[Request]] input to a computation that may 
  * return a [[Response]] or not, such that the response effect 
  * is an optional inside the effect of the request and response bodies.  
  * HTTP routes can conveniently be constructed from a partial function 
  * and combined as a `SemigroupK`.
  */
trait HttpRoutes { self =>
  def apply(req: Request): IO[Option[Response]]

  /** Transforms an [[HttpRoutes]] on its input.  The application of the
    * transformed function is suspended in `F` to permit more
    * efficient combination of routes via `SemigroupK`.
    *
    * @param f a function to apply to the [[Request]]
    * @param fa the [[HttpRoutes]] to transform
    * @return An [[HttpRoutes]] whose input is transformed by `f` before
    * being applied to `fa`
    */
  def local(f: Request => Request): HttpRoutes =
    req => IO.suspend(self(f(req)))

}

/** Functions for creating [[HttpRoutes]] kleislis. */
object HttpRoutes {

  /** Lifts a function into an [[HttpRoutes]].  The application of `run`
    * is suspended in `F` to permit more efficient combination of
    * routes via `SemigroupK`.
    *
    * @param run the function to lift
    * @return an [[HttpRoutes]] that wraps `run`
    */
  def apply(run: Request => IO[Option[Response]]): HttpRoutes =
    req => IO.suspend(run(req))

  /** Lifts an effectful [[Response]] into an [[HttpRoutes]].
    *
    * @param fr the effectful [[Response]] to lift
    * @return an [[HttpRoutes]] that always returns `fr`
    */
  def liftF(fr: IO[Option[Response]]): HttpRoutes = _ => fr

  /** Lifts a [[Response]] into an [[HttpRoutes]].
    *
    * @param response the [[Response]] to lift
    * @return an [[HttpRoutes]] that always responds with the given `response` inside an `IO` and `a `Some`
    */
  def pure(response: Response): HttpRoutes = _ => IO.pure(Some(response))
  

  /** Lifts a partial function into an [[HttpRoutes]].  The application of the
    * partial function is suspended in `F` to permit more efficient combination
    * of routes via `SemigroupK`.
    *
    * of routes, so only 1 section of routes is checked at a time.
    * @param pf the partial function to lift
    * @return An [[HttpRoutes]] that returns some [[Response]] in a `Some` in an `IO`
    * wherever `pf` is defined, an `OptionT.none` wherever it is not
    */
  def of(pf: PartialFunction[Request, IO[Response]]): HttpRoutes =
    req => IO.suspend(pf.lift(req).sequence)

  /** Lifts a partial function into an [[HttpRoutes]].  The application of the
    * partial function is not suspended in `F`, unlike [[of]]. This allows for less
    * constraints when not combining many routes.
    *
    * @param pf the partial function to lift
    * @return An [[HttpRoutes]] that returns some [[Response]] in an `OptionT[F, *]`
    * wherever `pf` is defined, an `OptionT.none` wherever it is not
    */
  def strict(pf: PartialFunction[Request, IO[Response]]): HttpRoutes =
    req => pf.lift(req).sequence

  /** An empty set of routes.  Always responds with `OptionT.none`.
    *
    */
  def empty: HttpRoutes = _ => IO.pure(None)
}
