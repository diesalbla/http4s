package org.http4s
package multipart

import org.http4s.headers._

final case class Multipart(parts: Vector[Part], boundary: Boundary = Boundary.create) {
  lazy val headers: Headers =
    Headers(
      List(
        `Transfer-Encoding`(TransferCoding.chunked),
        `Content-Type`(MediaType.multipartType("form-data", Some(boundary.value)))
      )
    )
}
