package org.http4s
package server
package middleware
package authentication

import cats.effect.IO
import cats.implicits._
import org.http4s.headers.Authorization

/**
  * Provides Basic Authentication from RFC 2617.
  */
object BasicAuth {

  /**
    * Validates a plaintext password (presumably by comparing it to a
    * hashed value).  A Some value indicates success; None indicates
    * the password failed to validate.
    */
  type BasicAuthenticator[A] = BasicCredentials => IO[Option[A]]

  /**
    * Construct authentication middleware that can validate the client-provided
    * plaintext password against something else (like a stored, hashed password).
    * @param realm The realm used for authentication purposes.
    * @param validate Function that validates a plaintext password
    * @return
    */
  def apply[A](realm: String, validate: BasicAuthenticator[A]): AuthMiddleware[A] =
    challenged(challenge(realm, validate))

  def challenge[A](realm: String, validate: BasicAuthenticator[A])
      : Request => IO[Either[Challenge, AuthedRequest[A]]] =
     { req =>
      validatePassword(validate, req).map {
        case Some(authInfo) =>
          Right(AuthedRequest(authInfo, req))
        case None =>
          Left(Challenge("Basic", realm, Map.empty))
      }
    }

  private def validatePassword[A](validate: BasicAuthenticator[A], req: Request): IO[Option[A]] =
    req.headers.get(Authorization) match {
      case Some(Authorization(BasicCredentials(username, password))) =>
        validate(BasicCredentials(username, password))
      case _ =>
        F.pure(None)
    }
}
