package org.http4s
package multipart

import cats.effect.{Blocker, ContextShift, IO}
import fs2.Stream
import fs2.io.readInputStream
import fs2.io.file.readAll
import fs2.text.utf8Encode
import java.io.{File, InputStream}
import java.net.URL
import org.http4s.headers.`Content-Disposition`

final case class Part(headers: Headers, body: Stream[IO, Byte]) extends Media {
  def name: Option[String] = headers.get(`Content-Disposition`).flatMap(_.parameters.get("name"))
  def filename: Option[String] =
    headers.get(`Content-Disposition`).flatMap(_.parameters.get("filename"))

}

object Part {
  private val ChunkSize = 8192

  val empty: Part = Part(Headers.empty, EmptyBody)

  def formData(name: String, value: String, headers: Header*): Part =
    Part(
      Headers(`Content-Disposition`("form-data", Map("name" -> name)) :: headers.toList),
      Stream.emit(value).through(utf8Encode))

  def fileData(
      name: String,
      file: File,
      blocker: Blocker,
      headers: Header*)(implicit cs: ContextShift[IO]): Part =
    fileData(name, file.getName, readAll[IO](file.toPath, blocker, ChunkSize), headers: _*)

  def fileData(
      name: String,
      resource: URL,
      blocker: Blocker,
    headers: Header*)(implicit cs: ContextShift[IO]): Part =
    fileData(name, resource.getPath.split("/").last, resource.openStream(), blocker, headers: _*)

  def fileData(
      name: String,
      filename: String,
      entityBody: EntityBody,
      headers: Header*): Part =
    Part(
      Headers(
        `Content-Disposition`("form-data", Map("name" -> name, "filename" -> filename)) ::
          Header("Content-Transfer-Encoding", "binary") ::
          headers.toList
      ),
      entityBody
    )

  // The InputStream is passed by name, and we open it in the by-name
  // argument in callers, so we can avoid lifting into an effect.  Exposing
  // this API publicly would invite unsafe use, and the `EntityBody` version
  // should be safe.
  private def fileData(
      name: String,
      filename: String,
      in: => InputStream,
      blocker: Blocker,
      headers: Header*)(implicit cs: ContextShift[IO]): Part =
    fileData(name, filename, readInputStream(IO.delay(in), ChunkSize, blocker), headers: _*)
}
