package org.http4s

import cats.Semigroup
import cats.effect.{Blocker, ContextShift, IO}
import cats.implicits._
import fs2.Stream
import fs2.io._
import fs2.io.file.readRange
import io.chrisdavenport.vault._
import java.io._
import java.net.URL
import org.http4s.Status.NotModified
import org.http4s.headers._
import org.log4s.getLogger

object StaticFile {
  private[this] val logger = getLogger

  val DefaultBufferSize = 10240

  def fromString(
    url: String,
    blocker: Blocker,
    req: Option[Request] = None)(implicit cs: ContextShift[IO]
  ): IO[Option[Response]] =
    fromFile(new File(url), blocker, req)

  def fromResource(
    name: String,
    blocker: Blocker,
    req: Option[Request] = None,
    preferGzipped: Boolean = false,
    classloader: Option[ClassLoader] = None)(
    implicit cs: ContextShift[IO]
  ): IO[Option[Response]] = {
    val loader = classloader.getOrElse(getClass.getClassLoader)

    val tryGzipped = preferGzipped && req.flatMap(_.headers.get(`Accept-Encoding`)).exists {
      acceptEncoding =>
        acceptEncoding.satisfiedBy(ContentCoding.gzip) || acceptEncoding.satisfiedBy(
          ContentCoding.`x-gzip`)
    }
    val normalizedName = name.split("/").filter(_.nonEmpty).mkString("/")

    def getResource(name: String) =
      IO.delay(Option(loader.getResource(name)))

    val gzUrl: IO[Option[URL]] =
      if (tryGzipped) getResource(normalizedName + ".gz") else IO.pure(None)

    gzUrl.flatMap {
      case Some(url) =>
        // Guess content type from the name without ".gz"
        val contentType = nameToContentType(normalizedName)
        val headers = `Content-Encoding`(ContentCoding.gzip) :: contentType.toList
        fromURL(url, blocker, req).map(_.removeHeader(`Content-Type`).putHeaders(headers: _*)).map(Some(_))

      case None => getResource(normalizedName).flatMap {
        case None => IO.pure(None)
        case Some(x) => fromURL(x, blocker, req).map(Some(_))
      }
    }
  }

  def fromURL(url: URL, blocker: Blocker, req: Option[Request] = None)(implicit cs: ContextShift[IO]): IO[Response] =
    IO.delay {
      val urlConn = url.openConnection
      val lastmod = HttpDate.fromEpochSecond(urlConn.getLastModified / 1000).toOption
      val ifModifiedSince = req.flatMap(_.headers.get(`If-Modified-Since`))
      val expired = (ifModifiedSince, lastmod).mapN(_.date < _).getOrElse(true)

      if (expired) {
        val lastModHeader: List[Header] = lastmod.map(`Last-Modified`(_)).toList
        val contentType = nameToContentType(url.getPath).toList
        val len = urlConn.getContentLengthLong
        val lenHeader =
          if (len >= 0) `Content-Length`.unsafeFromLong(len)
          else `Transfer-Encoding`(TransferCoding.chunked)
        val headers = Headers(lenHeader :: lastModHeader ::: contentType)

        Response(
          headers = headers,
          body = readInputStream(IO.delay(url.openStream), DefaultBufferSize, blocker)
        )
      } else {
        urlConn.getInputStream.close()
        Response(NotModified)
      }
    }

  def calcETag: File => IO[String] =
    f => IO.delay(
        if (f.isFile) s"${f.lastModified().toHexString}-${f.length().toHexString}" else "")

  def fromFile(
    f: File,
    blocker: Blocker,
    req: Option[Request] = None
  )(implicit cs: ContextShift[IO]
  ): IO[Option[Response]] =
    fromFile(f, DefaultBufferSize, blocker, req, calcETag)

  def fromFile(
      f: File,
      blocker: Blocker,
      req: Option[Request],
    etagCalculator: File => IO[String])(
    implicit cs: ContextShift[IO]
  ): IO[Option[Response]] =
    fromFile(f, DefaultBufferSize, blocker, req, etagCalculator)

  def fromFile(
      f: File,
      buffsize: Int,
      blocker: Blocker,
      req: Option[Request],
    etagCalculator: File => IO[String])(implicit cs: ContextShift[IO]
  ): IO[Option[Response]] =
    fromFile(f, 0, f.length(), buffsize, blocker, req, etagCalculator)

  def fromFile(
      f: File,
      start: Long,
      end: Long,
      buffsize: Int,
      blocker: Blocker,
      req: Option[Request],
      etagCalculator: File => IO[String])(
    implicit cs: ContextShift[IO]
  ): IO[Option[Response]] =
    for {
      etagCalc <- etagCalculator(f).map(et => ETag(et))
      res <- IO.delay {
        if (f.isFile) {
          require(
            start >= 0 && end >= start && buffsize > 0,
            s"start: $start, end: $end, buffsize: $buffsize")

          val lastModified = HttpDate.fromEpochSecond(f.lastModified / 1000).toOption

          notModified(req, etagCalc, lastModified).orElse {
            val (body, contentLength) =
              if (f.length() < end) (Stream.empty.covary[IO], 0L)
              else (fileToBody(f, start, end, blocker), end - start)

            val contentType = nameToContentType(f.getName)
            val hs = lastModified.map(lm => `Last-Modified`(lm)).toList :::
              `Content-Length`.fromLong(contentLength).toList :::
              contentType.toList ::: List(etagCalc)

            val r = Response(
              headers = Headers(hs),
              body = body,
              attributes = Vault.empty.insert(staticFileKey, f)
            )

            logger.trace(s"Static file generated response: $r")
            Some(r)
          }
        } else {
          None
        }
      }
    } yield res

  private def notModified(
      req: Option[Request],
      etagCalc: ETag,
      lastModified: Option[HttpDate]): Option[Response] = {
    implicit val conjunction = new Semigroup[Boolean] {
      def combine(x: Boolean, y: Boolean): Boolean = x && y
    }

    List(etagMatch(req, etagCalc), notModifiedSince(req, lastModified)).combineAll
      .filter(identity)
      .map(_ => Response(NotModified))
  }

  private def etagMatch(req: Option[Request], etagCalc: ETag) =
    for {
      r <- req
      etagHeader <- r.headers.get(`If-None-Match`)
      etagMatch = etagHeader.tags.exists(_.exists(_ == etagCalc.tag))
      _ = logger.trace(
        s"Matches `If-None-Match`: $etagMatch Previous ETag: ${etagHeader.value}, New ETag: $etagCalc")
    } yield etagMatch

  private def notModifiedSince(req: Option[Request], lastModified: Option[HttpDate]) =
    for {
      r <- req
      h <- r.headers.get(`If-Modified-Since`)
      lm <- lastModified
      notModified = h.date >= lm
      _ = logger.trace(
        s"Matches `If-Modified-Since`: $notModified. Request age: ${h.date}, Modified: $lm")
    } yield notModified

  private def fileToBody(
      f: File,
      start: Long,
      end: Long,
      blocker: Blocker
  )(implicit cs: ContextShift[IO]): EntityBody =
    readRange[IO](f.toPath, blocker, DefaultBufferSize, start, end)

  private def nameToContentType(name: String): Option[`Content-Type`] =
    name.lastIndexOf('.') match {
      case -1 => None
      case i => MediaType.forExtension(name.substring(i + 1)).map(`Content-Type`(_))
    }

  private[http4s] val staticFileKey = Key.newKey[IO, File].unsafeRunSync
}
