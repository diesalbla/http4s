package org.http4s.circe.middleware

import org.specs2.mutable.Specification
import cats.data.Kleisli
import cats.effect.IO
import org.http4s._

class JsonDebugErrorHandlerSpec extends Specification {
  "JsonDebugErrorHandler" should {
    "handle an unknown error" in {
      val service: Kleisli[IO, Request, Response] =
        Kleisli { (_: Request) =>
          IO.raiseError[Response](new Throwable("Boo!"))
        }
      val req: Request = Request(Method.GET)

      JsonDebugErrorHandler(service)
        .run(req)
        .attempt
        .unsafeRunSync must beRight
    }
    "handle an message failure" in {
      val service: Kleisli[IO, Request, Response] =
        Kleisli { (_: Request) =>
          IO.raiseError[Response](MalformedMessageBodyFailure("Boo!"))
        }
      val req: Request = Request(Method.GET)

      JsonDebugErrorHandler(service)
        .run(req)
        .attempt
        .unsafeRunSync must beRight
    }
  }
}
