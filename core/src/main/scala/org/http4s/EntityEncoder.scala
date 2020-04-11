package org.http4s

import cats.{Contravariant, Show}
import cats.effect.{Blocker, ContextShift, IO}
import cats.implicits._
import fs2.{Chunk, Stream}
import fs2.io.file.readAll
import fs2.io.readInputStream
import java.io._
import java.nio.CharBuffer
import java.nio.file.Path
import org.http4s.headers._
import org.http4s.multipart.MultipartEncoder
import scala.annotation.implicitNotFound

@implicitNotFound(
  "Cannot convert from ${A} to an Entity, because no EntityEncoder[${A}] instance could be found.")
trait EntityEncoder[A] { self =>

  /** Convert the type `A` to an [[EntityEncoder.Entity]] in the effect type `F` */
  def toEntity(a: A): Entity

  /** Headers that may be added to a [[Message]]
    *
    * Examples of such headers would be Content-Type.
    * __NOTE:__ The Content-Length header will be generated from the resulting Entity and thus should not be added.
    */
  def headers: Headers

  /** Make a new [[EntityEncoder]] using this type as a foundation */
  def contramap[B](f: B => A): EntityEncoder[B] = new EntityEncoder[B] {
    override def toEntity(a: B): Entity = self.toEntity(f(a))
    override def headers: Headers = self.headers
  }

  /** Get the [[org.http4s.headers.Content-Type]] of the body encoded by this [[EntityEncoder]], if defined the headers */
  def contentType: Option[`Content-Type`] = headers.get(`Content-Type`)

  /** Get the [[Charset]] of the body encoded by this [[EntityEncoder]], if defined the headers */
  def charset: Option[Charset] = headers.get(`Content-Type`).flatMap(_.charset)

  /** Generate a new EntityEncoder that will contain the `Content-Type` header */
  def withContentType(tpe: `Content-Type`): EntityEncoder[A] = new EntityEncoder[A] {
    override def toEntity(a: A): Entity = self.toEntity(a)
    override val headers: Headers = self.headers.put(tpe)
  }
}

object EntityEncoder {
  private val DefaultChunkSize = 4096

  /** summon an implicit [[EntityEncoder]] */
  def apply[A](implicit ev: EntityEncoder[A]): EntityEncoder[A] = ev

  /** Create a new [[EntityEncoder]] */
  def encodeBy[A](hs: Headers)(f: A => Entity): EntityEncoder[A] =
    new EntityEncoder[A] {
      override def toEntity(a: A): Entity = f(a)
      override def headers: Headers = hs
    }

  /** Create a new [[EntityEncoder]] */
  def encodeBy[A](hs: Header*)(f: A => Entity): EntityEncoder[A] = {
    val hdrs = if (hs.nonEmpty) Headers(hs.toList) else Headers.empty
    encodeBy(hdrs)(f)
  }

  /** Create a new [[EntityEncoder]]
    *
    * This constructor is a helper for types that can be serialized synchronously, for example a String.
    */
  def simple[A](hs: Header*)(toChunk: A => Chunk[Byte]): EntityEncoder[A] =
    encodeBy(hs: _*) { a =>
      val c = toChunk(a)
      Entity(Stream.chunk(c).covary[IO], Some(c.size.toLong))
    }

  /** Encodes a value from its Show instance.  Too broad to be implicit, too useful to not exist. */
  def showEncoder[A](
      implicit charset: Charset = DefaultCharset,
      show: Show[A]): EntityEncoder[A] = {
    val hdr = `Content-Type`(MediaType.text.plain).withCharset(charset)
    simple[A](hdr)(a => Chunk.bytes(show.show(a).getBytes(charset.nioCharset)))
  }

  def emptyEncoder[A]: EntityEncoder[A] =
    new EntityEncoder[A] {
      def toEntity(a: A): Entity = Entity.empty
      def headers: Headers = Headers.empty
    }

  /**
    * A stream encoder is intended for streaming, and does not calculate its
    * bodies in advance.  As such, it does not calculate the Content-Length in
    * advance.  This is for use with chunked transfer encoding.
    */
  implicit def streamEncoder[A](
      implicit W: EntityEncoder[A]): EntityEncoder[Stream[IO, A]] =
    new EntityEncoder[Stream[IO, A]] {
      override def toEntity(a: Stream[IO, A]): Entity =
        Entity(a.flatMap(W.toEntity(_).body))

      override def headers: Headers =
        W.headers.get(`Transfer-Encoding`) match {
          case Some(transferCoding) if transferCoding.hasChunked =>
            W.headers
          case _ =>
            W.headers.put(`Transfer-Encoding`(TransferCoding.chunked))
        }
    }

  implicit def unitEncoder: EntityEncoder[Unit] =
    emptyEncoder[Unit]

  implicit def stringEncoder(implicit charset: Charset = DefaultCharset): EntityEncoder[String] = {
    val hdr = `Content-Type`(MediaType.text.plain).withCharset(charset)
    simple(hdr)(s => Chunk.bytes(s.getBytes(charset.nioCharset)))
  }

  implicit def charArrayEncoder(
    implicit charset: Charset = DefaultCharset): EntityEncoder[Array[Char]] =
    stringEncoder.contramap(new String(_))

  implicit val chunkEncoder: EntityEncoder[Chunk[Byte]] =
    simple(`Content-Type`(MediaType.application.`octet-stream`))(identity)

  implicit val byteArrayEncoder: EntityEncoder[Array[Byte]] =
    chunkEncoder.contramap(Chunk.bytes)

  /** Encodes an entity body.  Chunking of the stream is preserved.  A
    * `Transfer-Encoding: chunked` header is set, as we cannot know
    * the content length without running the stream.
    */
  implicit val entityBodyEncoder: EntityEncoder[EntityBody] =
    encodeBy(`Transfer-Encoding`(TransferCoding.chunked)) { body =>
      Entity(body, None)
    }

  // TODO parameterize chunk size
  // TODO if Header moves to Entity, can add a Content-Disposition with the filename
  def fileEncoder(blocker: Blocker)(implicit cs: ContextShift[IO]): EntityEncoder[File] =
    filePathEncoder(blocker).contramap(_.toPath)

  // TODO parameterize chunk size
  // TODO if Header moves to Entity, can add a Content-Disposition with the filename
  def filePathEncoder(blocker: Blocker)(implicit cs: ContextShift[IO]): EntityEncoder[Path] =
    encodeBy[Path](`Transfer-Encoding`(TransferCoding.chunked)) { p =>
      Entity(readAll[IO](p, blocker, 4096)) //2 KB :P
    }

  // TODO parameterize chunk size
  def inputStreamEncoder[IS <: InputStream](
      blocker: Blocker)(implicit cs: ContextShift[IO]): EntityEncoder[IO[IS]] =
    entityBodyEncoder.contramap { (in: IO[IS]) =>
      readInputStream(in.widen[InputStream], DefaultChunkSize, blocker)
    }

  // TODO parameterize chunk size
  implicit def readerEncoder[R <: Reader](blocker: Blocker)(
    implicit cs: ContextShift[IO], charset: Charset = DefaultCharset
  ): EntityEncoder[IO[R]] =
    entityBodyEncoder.contramap { (fr: IO[R]) =>
      // Shared buffer
      val charBuffer = CharBuffer.allocate(DefaultChunkSize)
      def readToBytes(r: Reader): IO[Option[Chunk[Byte]]] =
        for {
          // Read into the buffer
          readChars <- blocker.delay[IO, Int](r.read(charBuffer))
        } yield {
          // Flip to read
          charBuffer.flip()

          if (readChars < 0) None
          else if (readChars == 0) Some(Chunk.empty)
          else {
            // Encode to bytes according to the charset
            val bb = charset.nioCharset.encode(charBuffer)
            // Read into a Chunk
            val b = new Array[Byte](bb.remaining())
            bb.get(b)
            Some(Chunk.bytes(b))
          }
        }

      def useReader(r: Reader) =
        Stream
          .eval(readToBytes(r))
          .repeat
          .unNoneTerminate
          .flatMap(Stream.chunk[IO, Byte])

      // The reader is closed at the end like InputStream
      Stream.bracket(fr)(r => IO.delay(r.close())).flatMap(useReader)
    }

  implicit val multipartEncoder = new MultipartEncoder

  implicit val entityEncoderContravariant: Contravariant[EntityEncoder] =
    new Contravariant[EntityEncoder] {
      override def contramap[A, B](r: EntityEncoder[A])(f: (B) => A): EntityEncoder[B] =
        r.contramap(f)
    }

  implicit val serverSentEventEncoder: EntityEncoder[EventStream] =
     entityBodyEncoder
      .contramap[EventStream](_.through(ServerSentEvent.encoder))
      .withContentType(`Content-Type`(MediaType.`text/event-stream`))
}
