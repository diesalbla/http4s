package org.http4s
package testing

import cats.MonadError
import cats.data.EitherT
import cats.implicits._
import org.http4s.headers._
import org.http4s.util.CaseInsensitiveString
import org.specs2.matcher._

@deprecated(
  "Discontinued. Inherits from vendored `RunTimedMatchers` that are now provided by specs-cats. The matchers that require them block threads and are disrecommended. What's left is insubstantial.",
  "0.21.0-RC2")
trait Http4sMatchers[F[_]] extends Matchers with RunTimedMatchers[F] {
  def haveStatus(expected: Status): Matcher[Response] =
    be_===(expected) ^^ { (r: Response) =>
      r.status.aka("the response status")
    }

  def returnStatus(s: Status): Matcher[F[Response]] =
    haveStatus(s) ^^ { (r: F[Response]) =>
      runAwait(r).aka("the returned")
    }

  def haveBody[A](a: ValueCheck[A])(
      implicit F: MonadError[F, Throwable],
      ee: EntityDecoder[A]): Matcher[Message] =
    returnValue(a) ^^ { (m: Message) =>
      m.as[A].aka("the message body")
    }

  def returnBody[A](a: ValueCheck[A])(
      implicit F: MonadError[F, Throwable],
      ee: EntityDecoder[A]): Matcher[F[Message]] =
    returnValue(a) ^^ { (m: F[Message]) =>
      m.flatMap(_.as[A]).aka("the returned message body")
    }

  def haveHeaders(a: Headers): Matcher[Message] =
    be_===(a) ^^ { (m: Message) =>
      m.headers.aka("the headers")
    }

  def containsHeader(h: Header): Matcher[Message] =
    beSome(h.value) ^^ { (m: Message) =>
      m.headers.get(h.name).map(_.value).aka("the particular header")
    }

  def doesntContainHeader(h: CaseInsensitiveString): Matcher[Message] =
    beNone ^^ { (m: Message) =>
      m.headers.get(h).aka("the particular header")
    }

  def haveMediaType(mt: MediaType): Matcher[Message] =
    beSome(mt) ^^ { (m: Message) =>
      m.headers.get(`Content-Type`).map(_.mediaType).aka("the media type header")
    }

  def haveContentCoding(c: ContentCoding): Matcher[Message] =
    beSome(c) ^^ { (m: Message) =>
      m.headers.get(`Content-Encoding`).map(_.contentCoding).aka("the content encoding header")
    }

  def returnRight[A, B](m: ValueCheck[B]): Matcher[EitherT[F, A, B]] =
    beRight(m) ^^ { (et: EitherT[F, A, B]) =>
      runAwait(et.value).aka("the either task")
    }

  def returnLeft[A, B](m: ValueCheck[A]): Matcher[EitherT[F, A, B]] =
    beLeft(m) ^^ { (et: EitherT[F, A, B]) =>
      runAwait(et.value).aka("the either task")
    }
}
