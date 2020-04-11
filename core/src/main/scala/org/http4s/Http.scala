package org.http4s

import cats.effect.IO

/** An HTTP is a function of a [[Request]] input to an IO computation of a [[Response]] output. 
  * This type is useful for writing middleware that are polymorphic over the return type F.
  *
  */
trait Http { self =>

  def apply(req: Request): IO[Response]

  /** Transforms an [[Http]] on its input.  The application of the
    * transformed function is suspended in `F` to permit more
    * efficient combination of routes via `SemigroupK`.
    *
    * @param f a function to apply to the [[Request]]
    * @param fa the [[Http]] to transform
    * @return An [[Http]] whose input is transformed by `f` before
    * being applied to `fa`
    */
  def local(f: Request => Request): Http =
    req => IO.suspend(self(f(req)))

}

/** Functions for creating [[Http]] kleislis. */
object Http {

  /** Lifts a function into an [[Http]].  The application of
    * `run` is suspended in `IO` to permit more efficient combination
    * of routes via `SemigroupK`.
    *
    * @param run the function to lift
    * @return an [[Http]] that suspends `run`.
    */
  def apply(run: Request => IO[Response]): Http =
    req => IO.suspend(run(req))

  /** Lifts an effectful [[Response]] into an [[Http]] kleisli.
    *
    * @param ioResponse the effectful [[Response]] to lift
    * @return an [[Http]] that always returns `fr`
    */
  def liftF(ioResponse: IO[Response]): Http = _ => ioResponse

  /** Lifts a [[Response]] into an [[Http]] kleisli.
    *
    * @param r the [[Response]] to lift
    * @return an [[Http]] that always returns `r` in effect `F`
    */
  def pure(response: Response): Http = _ => IO.pure(response)

  /** An app that always returns `404 Not Found`. */
  def notFound: Http = pure(Response.notFound)

}
