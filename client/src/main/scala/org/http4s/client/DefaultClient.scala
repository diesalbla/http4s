package org.http4s
package client

import cats.Applicative
import cats.data.{Kleisli, OptionT}
import cats.effect.{Bracket, Resource}
import cats.implicits._
import fs2.Stream
import org.http4s.Status.Successful
import org.http4s.headers.{Accept, MediaRangeAndQValue}

private[http4s] abstract class DefaultClient[F[_]](implicit F: Bracket[F, Throwable])
    extends Client[F] {
  def run(req: Request): Resource[F, Response]

  /** Submits a request, and provides a callback to process the response.
    *
    * @param req The request to submit
    * @param f   A callback for the response to req.  The underlying HTTP connection
    *            is disposed when the returned task completes.  Attempts to read the
    *            response body afterward will result in an error.
    * @return The result of applying f to the response to req
    */
  def fetch[A](req: Request)(f: Response => F[A]): F[A] =
    run(req).use(f)

  /** Submits a request, and provides a callback to process the response.
    *
    * @param req An effect of the request to submit
    * @param f A callback for the response to req.  The underlying HTTP connection
    *          is disposed when the returned task completes.  Attempts to read the
    *          response body afterward will result in an error.
    * @return The result of applying f to the response to req
    */
  def fetch[A](req: F[Request])(f: Response => F[A]): F[A] =
    req.flatMap(fetch(_)(f))

  /**
    * Returns this client as a [[Kleisli]].  All connections created by this
    * service are disposed on completion of callback task f.
    *
    * This method effectively reverses the arguments to `fetch`, and is
    * preferred when an HTTP client is composed into a larger Kleisli function,
    * or when a common response callback is used by many call sites.
    */
  def toKleisli[A](f: Response => F[A]): Kleisli[F, Request, A] =
    Kleisli(fetch(_)(f))

  @deprecated("Use toKleisli", "0.18")
  def toService[A](f: Response => F[A]): Service[F, Request, A] =
    toKleisli(f)

  /**
    * Returns this client as an [[HttpApp]].  It is the responsibility of
    * callers of this service to run the response body to dispose of the
    * underlying HTTP connection.
    *
    * This is intended for use in proxy servers.  `fetch`, `fetchAs`,
    * [[toKleisli]], and [[streaming]] are safer alternatives, as their
    * signatures guarantee disposal of the HTTP connection.
    */
  def toHttpApp: HttpApp[F] = Kleisli { req =>
    run(req).allocated.map {
      case (resp, release) =>
        resp.withBodyStream(resp.body.onFinalizeWeak(release))
    }
  }

  /**
    * Returns this client as an [[HttpService]].  It is the
    * responsibility of callers of this service to run the response
    * body to dispose of the underlying HTTP connection.
    *
    * This is intended for use in proxy servers.  `fetch`, `fetchAs`,
    * [[toKleisli]], and [[streaming]] are safer alternatives, as their
    * signatures guarantee disposal of the HTTP connection.
    */
  @deprecated("Use toHttpApp. Call `.mapF(OptionT.liftF)` if OptionT is really desired.", "0.19")
  def toHttpService: HttpService[F] =
    toHttpApp.mapF(OptionT.liftF(_))

  def stream(req: Request): Stream[IO, Response] =
    Stream.resource(run(req))

  def streaming[A](req: Request)(f: Response => Stream[IO, A]): Stream[IO, A] =
    stream(req).flatMap(f)

  def streaming[A](req: F[Request])(f: Response => Stream[IO, A]): Stream[IO, A] =
    Stream.eval(req).flatMap(stream).flatMap(f)

  def expectOr[A](req: Request)(onError: Response => F[Throwable])(
      implicit d: EntityDecoder[A]): F[A] = {
    val r = if (d.consumes.nonEmpty) {
      val m = d.consumes.toList
      req.putHeaders(Accept(MediaRangeAndQValue(m.head), m.tail.map(MediaRangeAndQValue(_)): _*))
    } else req
    fetch(r) {
      case Successful(resp) =>
        d.decode(resp, strict = false).leftWiden[Throwable].rethrowT
      case failedResponse =>
        onError(failedResponse).flatMap(F.raiseError)
    }
  }

  /**
    * Submits a request and decodes the response on success.  On failure, the
    * status code is returned.  The underlying HTTP connection is closed at the
    * completion of the decoding.
    */
  def expect[A](req: Request)(implicit d: EntityDecoder[A]): F[A] =
    expectOr(req)(defaultOnError)

  def expectOr[A](req: F[Request])(onError: Response => F[Throwable])(
      implicit d: EntityDecoder[A]): F[A] =
    req.flatMap(expectOr(_)(onError))

  def expect[A](req: F[Request])(implicit d: EntityDecoder[A]): F[A] =
    expectOr(req)(defaultOnError)

  def expectOr[A](uri: Uri)(onError: Response => F[Throwable])(
      implicit d: EntityDecoder[A]): F[A] =
    expectOr(Request(Method.GET, uri))(onError)

  /**
    * Submits a GET request to the specified URI and decodes the response on
    * success.  On failure, the status code is returned.  The underlying HTTP
    * connection is closed at the completion of the decoding.
    */
  def expect[A](uri: Uri)(implicit d: EntityDecoder[A]): F[A] =
    expectOr(uri)(defaultOnError)

  def expectOr[A](s: String)(onError: Response => F[Throwable])(
      implicit d: EntityDecoder[A]): F[A] =
    Uri.fromString(s).fold(F.raiseError, uri => expectOr[A](uri)(onError))

  /**
    * Submits a GET request to the URI specified by the String and decodes the
    * response on success.  On failure, the status code is returned.  The
    * underlying HTTP connection is closed at the completion of the decoding.
    */
  def expect[A](s: String)(implicit d: EntityDecoder[A]): F[A] =
    expectOr(s)(defaultOnError)

  def expectOptionOr[A](req: Request)(onError: Response => F[Throwable])(
      implicit d: EntityDecoder[A]): F[Option[A]] = {
    val r = if (d.consumes.nonEmpty) {
      val m = d.consumes.toList
      req.putHeaders(Accept(MediaRangeAndQValue(m.head), m.tail.map(MediaRangeAndQValue(_)): _*))
    } else req
    fetch(r) {
      case Successful(resp) =>
        d.decode(resp, strict = false).leftWiden[Throwable].rethrowT.map(_.some)
      case failedResponse =>
        failedResponse.status match {
          case Status.NotFound => Option.empty[A].pure[F]
          case Status.Gone => Option.empty[A].pure[F]
          case _ => onError(failedResponse).flatMap(F.raiseError)
        }
    }
  }

  def expectOption[A](req: Request)(implicit d: EntityDecoder[A]): F[Option[A]] =
    expectOptionOr(req)(defaultOnError)

  /**
    * Submits a request and decodes the response, regardless of the status code.
    * The underlying HTTP connection is closed at the completion of the
    * decoding.
    */
  def fetchAs[A](req: Request)(implicit d: EntityDecoder[A]): F[A] = {
    val r = if (d.consumes.nonEmpty) {
      val m = d.consumes.toList
      req.putHeaders(Accept(MediaRangeAndQValue(m.head), m.tail.map(MediaRangeAndQValue(_)): _*))
    } else req
    fetch(r) { resp =>
      d.decode(resp, strict = false).leftWiden[Throwable].rethrowT
    }
  }

  /**
    * Submits a request and decodes the response, regardless of the status code.
    * The underlying HTTP connection is closed at the completion of the
    * decoding.
    */
  def fetchAs[A](req: F[Request])(implicit d: EntityDecoder[A]): F[A] =
    req.flatMap(fetchAs(_)(d))

  /** Submits a request and returns the response status */
  def status(req: Request): F[Status] =
    fetch(req)(resp => F.pure(resp.status))

  /** Submits a request and returns the response status */
  def status(req: F[Request]): F[Status] =
    req.flatMap(status)

  /** Submits a GET request to the URI and returns the response status */
  override def statusFromUri(uri: Uri): F[Status] =
    status(Request(uri = uri))

  /** Submits a GET request to the URI and returns the response status */
  override def statusFromString(s: String): F[Status] =
    F.fromEither(Uri.fromString(s)).flatMap(statusFromUri)

  /** Submits a request and returns true if and only if the response status is
    * successful */
  def successful(req: Request): F[Boolean] =
    status(req).map(_.isSuccess)

  /** Submits a request and returns true if and only if the response status is
    * successful */
  def successful(req: F[Request]): F[Boolean] =
    req.flatMap(successful)

  @deprecated("Use expect", "0.14")
  def prepAs[A](req: Request)(implicit d: EntityDecoder[A]): F[A] =
    fetchAs(req)(d)

  /** Submits a GET request, and provides a callback to process the response.
    *
    * @param uri The URI to GET
    * @param f A callback for the response to a GET on uri.  The underlying HTTP connection
    *          is disposed when the returned task completes.  Attempts to read the
    *          response body afterward will result in an error.
    * @return The result of applying f to the response to req
    */
  def get[A](uri: Uri)(f: Response => F[A]): F[A] =
    fetch(Request(Method.GET, uri))(f)

  /**
    * Submits a request and decodes the response on success.  On failure, the
    * status code is returned.  The underlying HTTP connection is closed at the
    * completion of the decoding.
    */
  def get[A](s: String)(f: Response => F[A]): F[A] =
    Uri.fromString(s).fold(F.raiseError, uri => get(uri)(f))

  /**
    * Submits a GET request and decodes the response.  The underlying HTTP
    * connection is closed at the completion of the decoding.
    */
  @deprecated("Use expect", "0.14")
  def getAs[A](uri: Uri)(implicit d: EntityDecoder[A]): F[A] =
    fetchAs(Request(Method.GET, uri))(d)

  @deprecated("Use expect", "0.14")
  def getAs[A](s: String)(implicit d: EntityDecoder[A]): F[A] =
    Uri.fromString(s).fold(F.raiseError, uri => expect[A](uri))

  @deprecated("Use expect", "0.14")
  def prepAs[T](req: F[Request])(implicit d: EntityDecoder[T]): F[T] =
    fetchAs(req)

  private def defaultOnError(resp: Response)(implicit F: Applicative[F]): F[Throwable] =
    F.pure(UnexpectedStatus(resp.status))
}
