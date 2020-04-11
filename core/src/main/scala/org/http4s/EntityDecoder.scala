package org.http4s

import cats.{SemigroupK}
import cats.effect.{Blocker, ContextShift, IO}
import cats.implicits._
import fs2._
import fs2.io.file.writeAll
import java.io.File
import org.http4s.multipart.{Multipart, MultipartDecoder}
import scala.annotation.implicitNotFound

/** A type that can be used to decode a [[Message]]
  * EntityDecoder is used to attempt to decode a [[Message]] returning the
  * entire resulting A. If an error occurs it will result in a failed effect.
  * The default decoders provided here are not streaming, but one could implement
  * a streaming decoder by having the value of A be some kind of streaming construct.
  *
  * @tparam T result type produced by the decoder
  */
@implicitNotFound(
  "Cannot decode into a value of type ${T}, because no EntityDecoder[${F}, ${T}] instance could be found.")
trait EntityDecoder[T] { self =>

  /** Attempt to decode the body of the [[Message]] */
  def decode(m: Media, strict: Boolean): DecodeResult[T]

  /** The [[MediaRange]]s this [[EntityDecoder]] knows how to handle */
  def consumes: Set[MediaRange]

  /** Make a new [[EntityDecoder]] by mapping the output result */
  def map[T2](f: T => T2): EntityDecoder[T2] = new EntityDecoder[T2] {
    override def consumes: Set[MediaRange] = self.consumes
    override def decode(m: Media, strict: Boolean): DecodeResult[T2] =
      self.decode(m, strict).map(_.map(f))
  }

  def flatMapR[T2](f: T => DecodeResult[T2]): EntityDecoder[T2] =
    new EntityDecoder[T2] {
      override def decode(m: Media, strict: Boolean): DecodeResult[T2] =
        self.decode(m, strict).flatMap {
          case Right(t) => f(t)
          case Left(e) => IO.pure(Left(e))
        }

      override def consumes: Set[MediaRange] = self.consumes
    }

  def handleError(f: DecodeFailure => T): EntityDecoder[T] = transform {
    case Left(e) => Right(f(e))
    case r @ Right(_) => r
  }

  def handleErrorWith(f: DecodeFailure => DecodeResult[T]): EntityDecoder[T] = transformWith {
    case Left(e) => f(e)
    case Right(r) => DecodeResult.success(r)
  }

  def bimap[T2](f: DecodeFailure => DecodeFailure, s: T => T2): EntityDecoder[T2] =
    transform {
      case Left(e) => Left(f(e))
      case Right(r) => Right(s(r))
    }

  def transform[T2](t: Either[DecodeFailure, T] => Either[DecodeFailure, T2]): EntityDecoder[T2] =
    new EntityDecoder[T2] {
      override def consumes: Set[MediaRange] = self.consumes
      override def decode(m: Media, strict: Boolean): DecodeResult[T2] =
        self.decode(m, strict).map(t)
    }

  def biflatMap[T2](
    f: DecodeFailure => DecodeResult[T2],
    s: T => DecodeResult[T2]
  ): EntityDecoder[T2] =
    transformWith {
      case Left(e) => f(e)
      case Right(r) => s(r)
    }

  def transformWith[T2](f: Either[DecodeFailure, T] => DecodeResult[T2]): EntityDecoder[T2] =
    new EntityDecoder[T2] {
      override def consumes: Set[MediaRange] = self.consumes
      override def decode(m: Media, strict: Boolean): DecodeResult[T2] =
        self.decode(m, strict).flatMap(f)
    }

  /** Combine two [[EntityDecoder]]'s
    *
    * The new [[EntityDecoder]] will first attempt to determine if it can perform the decode,
    * and if not, defer to the second [[EntityDecoder]]
    *
    * @param other backup [[EntityDecoder]]
    */
  def orElse[T2 >: T](other: EntityDecoder[T2]): EntityDecoder[T2] =
    widen[T2] <+> other

  /** true if this [[EntityDecoder]] knows how to decode the provided [[MediaType]] */
  def matchesMediaType(mediaType: MediaType): Boolean =
    consumes.exists(_.satisfiedBy(mediaType))

  def widen[T2 >: T]: EntityDecoder[T2] =
    this.asInstanceOf[EntityDecoder[T2]]
}

/** EntityDecoder is used to attempt to decode an [[EntityBody]]
  * This companion object provides a way to create `new EntityDecoder`s along
  * with some commonly used instances which can be resolved implicitly.
  */
object EntityDecoder {
  // This is not a real media type but will still be matched by `*/*`
  private val UndefinedMediaType = new MediaType("UNKNOWN", "UNKNOWN")

  /** summon an implicit [[EntityDecoder]] */
  def apply[F[_], T](implicit ev: EntityDecoder[T]): EntityDecoder[T] = ev

  implicit def semigroupKForEntityDecoder: SemigroupK[EntityDecoder[*]] =
    new SemigroupK[EntityDecoder[*]] {
      override def combineK[T](
          a: EntityDecoder[T],
          b: EntityDecoder[T]): EntityDecoder[T] = new EntityDecoder[T] {
        override def decode(m: Media, strict: Boolean): DecodeResult[T] = {
          val mediaType = m.contentType.fold(UndefinedMediaType)(_.mediaType)

          if (a.matchesMediaType(mediaType))
            a.decode(m, strict)
          else
            b.decode(m, strict).map { _.leftMap {
              case MediaTypeMismatch(actual, expected) =>
                MediaTypeMismatch(actual, expected ++ a.consumes)
              case MediaTypeMissing(expected) =>
                MediaTypeMissing(expected ++ a.consumes)
              case other => other
            }}
        }

        override def consumes: Set[MediaRange] = a.consumes ++ b.consumes
      }
    }

  /** Create a new [[EntityDecoder]]
    *
    * The new [[EntityDecoder]] will attempt to decode messages of type `T`
    * only if the [[Message]] satisfies the provided [[MediaRange]].
    *
    * Exceptions thrown by `f` are not caught.  Care should be taken
    * that recoverable errors are returned as a
    * [[DecodeResult.failure]], or that system errors are raised in `F`.
    */
  def decodeBy[T](r1: MediaRange, rs: MediaRange*)(
    f: Media => DecodeResult[T]): EntityDecoder[T] = new EntityDecoder[T] {
    override def decode(m: Media, strict: Boolean): DecodeResult[T] =
      if (strict) {
        m.contentType match {
          case Some(c) if matchesMediaType(c.mediaType) => f(m)
          case Some(c) => DecodeResult.failure(MediaTypeMismatch(c.mediaType, consumes))
          case None if matchesMediaType(UndefinedMediaType) => f(m)
          case None => DecodeResult.failure(MediaTypeMissing(consumes))
        }
      } else {
        f(m)
      }

    override val consumes: Set[MediaRange] = (r1 +: rs).toSet
  }

  /** Helper method which simply gathers the body into a single Chunk */
  def collectBinary(m: Media): DecodeResult[Chunk[Byte]] =
    DecodeResult.success(m.body.chunks.compile.toVector.map(Chunk.concatBytes))

  /** Decodes a message to a String */
  def decodeString(m: Media)(implicit defaultCharset: Charset = DefaultCharset): IO[String] =
    m.bodyAsText.compile.foldMonoid

  /////////////////// Instances //////////////////////////////////////////////

  /** Provides a mechanism to fail decoding */
  def error[T](t: Throwable): EntityDecoder[T] =
    new EntityDecoder[T] {
      override def decode(m: Media, strict: Boolean): DecodeResult[T] =
        m.body.compile.drain *> IO.raiseError(t)
      override def consumes: Set[MediaRange] = Set.empty
    }

  implicit val binary: EntityDecoder[Chunk[Byte]] =
    EntityDecoder.decodeBy(MediaRange.`*/*`)(collectBinary)

  implicit val byteArrayDecoder: EntityDecoder[Array[Byte]] =
    binary.map(_.toArray)

  implicit def text(implicit defaultCharset: Charset = DefaultCharset): EntityDecoder[String] =
    EntityDecoder.decodeBy(MediaRange.`text/*`)(msg =>
      collectBinary(msg).map(_.map(chunk =>
        new String(chunk.toArray, msg.charset.getOrElse(defaultCharset).nioCharset)))
    )

  implicit def charArrayDecoder: EntityDecoder[Array[Char]] =
    text.map(_.toArray)

  // File operations
  def binFile(file: File, blocker: Blocker)(implicit cs: ContextShift[IO]): EntityDecoder[File] =
    EntityDecoder.decodeBy(MediaRange.`*/*`) { msg =>
      val pipe = writeAll[IO](file.toPath, blocker)
      DecodeResult.success(msg.body.through(pipe).compile.drain).map(_.map(_ => file))
    }

  def textFile(file: File, blocker: Blocker)(implicit cs: ContextShift[IO]): EntityDecoder[File] =
    EntityDecoder.decodeBy(MediaRange.`text/*`) { msg =>
      val pipe = writeAll[IO](file.toPath, blocker)
      DecodeResult.success(msg.body.through(pipe).compile.drain).map(_.map(_ => file))
    }

  implicit val multipart: EntityDecoder[Multipart] = MultipartDecoder.decoder

  /** An entity decoder that ignores the content and returns unit. */
  implicit def void: EntityDecoder[Unit] =
    EntityDecoder.decodeBy(MediaRange.`*/*`) { msg =>
      DecodeResult.success(msg.body.drain.compile.drain)
    }
}
