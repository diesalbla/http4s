package org.http4s
package server
package middleware

import cats.~>
import cats.arrow.FunctionK
import cats.data.{Kleisli, OptionT}
import cats.effect.{Bracket, Concurrent, ExitCase, Sync}
import cats.effect.implicits._
import cats.effect.Sync._
import cats.effect.concurrent.Ref
import cats.implicits._
import fs2.{Chunk, Stream}
import org.http4s.util.CaseInsensitiveString
import org.log4s.getLogger

/**
  * Simple middleware for logging responses as they are processed
  */
object ResponseLogger {
  private[this] val logger = getLogger

  def apply[G[_], F[_], A](
      logHeaders: Boolean,
      logBody: Boolean,
      fk: F ~> G,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains,
      logAction: Option[String => F[Unit]] = None)(http: Kleisli[G, A, Response])(
      implicit G: Bracket[G, Throwable],
      F: Concurrent[F]): Kleisli[G, A, Response] = {
    val fallback: String => F[Unit] = s => Sync[F].delay(logger.info(s))
    val log = logAction.fold(fallback)(identity)
    Kleisli[G, A, Response] { req =>
      http(req)
        .flatMap { response =>
          val out =
            if (!logBody)
              Logger
                .logMessage[F, Response](response)(logHeaders, logBody, redactHeadersWhen)(log)
                .as(response)
            else
              Ref[F].of(Vector.empty[Chunk[Byte]]).map { vec =>
                val newBody = Stream
                  .eval(vec.get)
                  .flatMap(v => Stream.emits(v).covary[F])
                  .flatMap(c => Stream.chunk(c).covary[F])

                response.copy(
                  body = response.body
                  // Cannot Be Done Asynchronously - Otherwise All Chunks May Not Be Appended Previous to Finalization
                    .observe(_.chunks.flatMap(c => Stream.eval_(vec.update(_ :+ c))))
                    .onFinalizeWeak {
                      Logger.logMessage[F, Response](response.withBodyStream(newBody))(
                        logHeaders,
                        logBody,
                        redactHeadersWhen)(log)
                    }
                )
              }
          fk(out)
        }
        .guaranteeCase {
          case ExitCase.Error(t) => fk(log(s"service raised an error: ${t.getClass}"))
          case ExitCase.Canceled => fk(log(s"service cancelled response for request [$req]"))
          case ExitCase.Completed => G.unit
        }
    }
  }

  def httpApp[F[_]: Concurrent, A](
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains,
      logAction: Option[String => F[Unit]] = None)(
      httpApp: Kleisli[F, A, Response]): Kleisli[F, A, Response] =
    apply(logHeaders, logBody, FunctionK.id[F], redactHeadersWhen, logAction)(httpApp)

  def httpRoutes[F[_]: Concurrent, A](
      logHeaders: Boolean,
      logBody: Boolean,
      redactHeadersWhen: CaseInsensitiveString => Boolean = Headers.SensitiveHeaders.contains,
      logAction: Option[String => F[Unit]] = None)(
      httpRoutes: Kleisli[OptionT[F, *], A, Response]): Kleisli[OptionT[F, *], A, Response] =
    apply(logHeaders, logBody, OptionT.liftK[F], redactHeadersWhen, logAction)(httpRoutes)
}
