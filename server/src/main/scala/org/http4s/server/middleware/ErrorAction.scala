package org.http4s.server
package middleware

import cats.data.Kleisli
import cats._
import cats.implicits._
import org.http4s._

object ErrorAction {
  def apply[F[_]: ApplicativeError[*[_], Throwable], G[_], B](
      k: Kleisli[F, Request, B],
      f: (Request, Throwable) => F[Unit]
  ): Kleisli[F, Request, B] =
    Kleisli { req =>
      k.run(req).onError { case e => f(req, e) }
    }

  def log[F[_]: ApplicativeError[*[_], Throwable], G[_], B](
      http: Kleisli[F, Request, B],
      messageFailureLogAction: (Throwable, => String) => F[Unit],
      serviceErrorLogAction: (Throwable, => String) => F[Unit]
  ): Kleisli[F, Request, B] =
    apply(
      http, {
        case (req, mf: MessageFailure) =>
          messageFailureLogAction(
            mf,
            s"""Message failure handling request: ${req.method} ${req.pathInfo} from ${req.remoteAddr
              .getOrElse("<unknown>")}"""
          )
        case (req, e) =>
          serviceErrorLogAction(
            e,
            s"""Error servicing request: ${req.method} ${req.pathInfo} from ${req.remoteAddr
              .getOrElse("<unknown>")}"""
          )
      }
    )
}
