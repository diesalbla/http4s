package org.http4s

import cats._
import cats.effect.IO
import cats.implicits._

final case class ContextRequest[A](context: A, req: Request)

object ContextRequest {
  def apply[T](getContext: Request => IO[T]): Request => IO[ContextRequest[T]] =
    request => getContext(request).map(ctx => ContextRequest(ctx, request))

  implicit def contextRequestInstances[F[_]]: NonEmptyTraverse[ContextRequest[*]] =
    new NonEmptyTraverse[ContextRequest[*]] {
      override def foldLeft[A, B](fa: ContextRequest[A], b: B)(f: (B, A) => B): B =
        f(b, fa.context)
      override def foldRight[A, B](fa: ContextRequest[A], lb: Eval[B])(
          f: (A, Eval[B]) => Eval[B]): Eval[B] =
        f(fa.context, lb)
      override def nonEmptyTraverse[G[_]: Apply, A, B](fa: ContextRequest[A])(
          f: A => G[B]): G[ContextRequest[B]] =
        f(fa.context).map(b => ContextRequest(b, fa.req))
      def reduceLeftTo[A, B](fa: ContextRequest[A])(f: A => B)(g: (B, A) => B): B =
        f(fa.context)
      def reduceRightTo[A, B](fa: ContextRequest[A])(f: A => B)(
          g: (A, Eval[B]) => Eval[B]): Eval[B] =
        Eval.later(f(fa.context))
    }
}

