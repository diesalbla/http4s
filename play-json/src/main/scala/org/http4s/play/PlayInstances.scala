package org.http4s.play

import cats.effect.Sync
import fs2.Chunk
import org.http4s.headers.`Content-Type`
import org.http4s.{
  DecodeResult,
  EntityDecoder,
  EntityEncoder,
  InvalidMessageBodyFailure,
  MediaType,
  Message,
  Uri,
  jawn
}
import org.typelevel.jawn.support.play.Parser.facade
import play.api.libs.json._

trait PlayInstances {
  def jsonOf[A](implicit decoder: Reads[A]): EntityDecoder[A] =
    jsonDecoder.flatMapR { json =>
      decoder
        .reads(json)
        .fold(
          _ =>
            DecodeResult.failure(InvalidMessageBodyFailure(s"Could not decode JSON: $json", None)),
          DecodeResult.success(_)
        )
    }

  implicit def jsonDecoder: EntityDecoder[JsValue] =
    jawn.jawnDecoder[JsValue]

  def jsonEncoderOf[A: Writes]: EntityEncoder[A] =
    jsonEncoder[F].contramap[A](Json.toJson(_))

  implicit def jsonEncoder(implicit x: EntityEncoder[JsValue]) =
    EntityEncoder[Chunk[Byte]]
      .contramap[JsValue] { json =>
        val bytes = json.toString.getBytes("UTF8")
        Chunk.bytes(bytes)
      }
      .withContentType(`Content-Type`(MediaType.application.json))

  implicit val writesUri: Writes[Uri] =
    Writes.contravariantfunctorWrites.contramap[String, Uri](implicitly[Writes[String]], _.toString)

  implicit val readsUri: Reads[Uri] =
    implicitly[Reads[String]].flatMap { str =>
      Uri
        .fromString(str)
        .fold(
          _ =>
            new Reads[Uri] {
              def reads(json: JsValue): JsResult[Uri] = JsError("Invalid uri")
            },
          Reads.pure(_)
        )
    }

  implicit class MessageSyntax(self: Message) {
    def decodeJson[A: Reads]: IO[A] =
      self.as(implicitly, jsonOf[A])
  }
}
