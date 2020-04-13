package org.http4s

import cats.effect.IO
import fs2._
import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.StandardCharsets

package object util {
  private val utf8Bom: Chunk[Byte] = Chunk(0xef.toByte, 0xbb.toByte, 0xbf.toByte)

  def decode(charset: Charset): Pipe[IO, Byte, String] = {
    val decoder = charset.nioCharset.newDecoder
    val maxCharsPerByte = math.ceil(decoder.maxCharsPerByte().toDouble).toInt
    val avgBytesPerChar = math.ceil(1.0 / decoder.averageCharsPerByte().toDouble).toInt
    val charBufferSize = 128

    _.repeatPull[String] {
      _.unconsN(charBufferSize * avgBytesPerChar, allowFewer = true).flatMap {
        case None =>
          val charBuffer = CharBuffer.allocate(1)
          decoder.decode(ByteBuffer.allocate(0), charBuffer, true)
          decoder.flush(charBuffer)
          val outputString = charBuffer.flip().toString
          if (outputString.isEmpty) Pull.done.as(None)
          else Pull.output1(outputString).as(None)
        case Some((chunk, stream)) =>
          if (chunk.nonEmpty) {
            val chunkWithoutBom = skipByteOrderMark(chunk)
            val bytes = chunkWithoutBom.toArray
            val byteBuffer = ByteBuffer.wrap(bytes)
            val charBuffer = CharBuffer.allocate(bytes.length * maxCharsPerByte)
            decoder.decode(byteBuffer, charBuffer, false)
            val nextStream = stream.consChunk(Chunk.byteBuffer(byteBuffer.slice()))
            Pull.output1(charBuffer.flip().toString).as(Some(nextStream))
          } else {
            Pull.output(Chunk.empty[String]).as(Some(stream))
          }
      }
    }
  }

  private def skipByteOrderMark(chunk: Chunk[Byte]): Chunk[Byte] =
    if (chunk.size >= 3 && chunk.take(3) == utf8Bom) {
      chunk.drop(3)
    } else chunk

  /** Converts ASCII encoded byte stream to a stream of `String`. */
  private[http4s] def asciiDecode: Pipe[IO, Byte, String] =
    _.chunks.through(asciiDecodeC)

  private def asciiCheck(b: Byte) = 0x80 & b

  /** Converts ASCII encoded `Chunk[Byte]` inputs to `String`. */
  private[http4s] def asciiDecodeC: Pipe[IO, Chunk[Byte], String] = { in =>
    def tailRecAsciiCheck(i: Int, bytes: Array[Byte]): Stream[IO, String] =
      if (i == bytes.length)
        Stream.emit(new String(bytes, StandardCharsets.US_ASCII))
      else {
        if (asciiCheck(bytes(i)) == 0x80) {
          Stream.raiseError[IO](
            new IllegalArgumentException("byte stream is not encodable as ascii bytes"))
        } else {
          tailRecAsciiCheck(i + 1, bytes)
        }
      }

    in.flatMap(c => tailRecAsciiCheck(0, c.toArray))
  }

  /** Constructs an assertion error with a reference back to our issue tracker. Use only with head hung low. */
  def bug(message: String): AssertionError =
    new AssertionError(
      s"This is a bug. Please report to https://github.com/http4s/http4s/issues: ${message}")

  /* This is nearly identical to the hashCode of java.lang.String, but converting
   * to lower case on the fly to avoid copying `value`'s character storage.
   */
  def hashLower(s: String): Int = {
    var h = 0
    var i = 0
    val len = s.length
    while (i < len) {
      // Strings are equal igoring case if either their uppercase or lowercase
      // forms are equal. Equality of one does not imply the other, so we need
      // to go in both directions. A character is not guaranteed to make this
      // round trip, but it doesn't matter as long as all equal characters
      // hash the same.
      h = h * 31 + Character.toLowerCase(Character.toUpperCase(s.charAt(i)))
      i += 1
    }
    h
  }
}
