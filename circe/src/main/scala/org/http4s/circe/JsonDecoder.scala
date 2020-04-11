package org.http4s.circe

import cats.effect.Sync
import org.http4s._
import io.circe._

/**
  * F-algebra for separating the Sync required for extracting
  * the Json from the body. As such if F is Sync at some layer,
  * then this can be used to extract without the lower layer
  * needing to be aware of the strong constraint.
 **/
trait JsonDecoder {
  def asJson(m: Message): IO[Json]
  def asJsonDecode[A: Decoder](m: Message): IO[A]
}

object JsonDecoder {
  def apply[F[_]](implicit ev: JsonDecoder): JsonDecoder = ev

  implicit def impl: JsonDecoder = new JsonDecoder {
    def asJson(m: Message): IO[Json] = m.as[Json]
    def asJsonDecode[A: Decoder](m: Message): F[A] = m.decodeJson
  }
}
