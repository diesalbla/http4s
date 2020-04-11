package org.http4s

import cats.effect.IO
import fs2.Stream
import fs2.text.utf8Decode
import org.http4s.headers._
import org.http4s.util.decode

trait Media {
  def body: EntityBody
  def headers: Headers

  final def bodyAsText(implicit defaultCharset: Charset = DefaultCharset): Stream[IO, String] =
    charset.getOrElse(defaultCharset) match {
      case Charset.`UTF-8` =>
        // suspect this one is more efficient, though this is superstition
        body.through(utf8Decode)
      case cs =>
        body.through(decode(cs))
    }

  final def contentType: Option[`Content-Type`] =
    headers.get(`Content-Type`)

  final def contentLength: Option[Long] =
    headers.get(`Content-Length`).map(_.length)

  final def charset: Option[Charset] =
    contentType.flatMap(_.charset)

  // Decoding methods

  /** Decode the [[Media]] to the specified type
    *
    * @param decoder [[EntityDecoder]] used to decode the [[Media]]
    * @tparam T type of the result
    * @return the effect which will generate the `DecodeResult[T]`
    */
  final def attemptAs[T](implicit decoder: EntityDecoder[T]): DecodeResult[T] =
    decoder.decode(this, strict = false)

  /** Decode the [[Media]] to the specified type
    *
    * If no valid [[Status]] has been described, allow Ok
    *
    * @param decoder [[EntityDecoder]] used to decode the [[Media]]
    * @tparam A type of the result
    * @return the effect which will generate the A
    */
  final def as[A](implicit decoder: EntityDecoder[A]): IO[A] =
    // n.b. this will be better with redeem in Cats-2.0
    attemptAs.flatMap(_.fold(IO.raiseError, IO.pure))
}

object Media {
  def apply(b: EntityBody, h: Headers): Media = new Media {
    def body = b
    def headers: Headers = h
  }
}
