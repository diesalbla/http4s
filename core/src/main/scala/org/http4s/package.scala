package org

import cats.effect.IO
import fs2.Stream

package object http4s { // scalastyle:ignore

  type AuthScheme = util.CaseInsensitiveString

  type EntityBody = Stream[IO, Byte]

  val EmptyBody: EntityBody = Stream.empty[IO]

  val ApiVersion: Http4sVersion = Http4sVersion(BuildInfo.apiVersion._1, BuildInfo.apiVersion._2)

  type DecodeResult[A] = IO[Either[DecodeFailure, A]]

  type ParseResult[+A] = Either[ParseFailure, A]

  val DefaultCharset = Charset.`UTF-8`

  type AuthedRequest[T] = ContextRequest[T]
  type AuthedRoutes[T] = ContextRoutes[T]

  type Callback[A] = Either[Throwable, A] => Unit

  /** A stream of server-sent events */
  type EventStream = Stream[IO, ServerSentEvent]

}
