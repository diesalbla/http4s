package org.http4s
package client.testroutes

import cats.effect._
import cats.implicits._
import fs2._
import org.http4s.Status._
import org.http4s.internal.CollectionCompat
import scala.concurrent.duration._

object GetRoutes {
  val SimplePath = "/simple"
  val ChunkedPath = "/chunked"
  val DelayedPath = "/delayed"
  val NoContentPath = "/no-content"
  val NotFoundPath = "/not-found"
  val EmptyNotFoundPath = "/empty-not-found"
  val InternalServerErrorPath = "/internal-server-error"

  def getPaths(implicit timer: Timer[IO]): Map[String, Response] =
    CollectionCompat.mapValues(
      Map(
        SimplePath -> Response(Ok).withEntity("simple path").pure[IO],
        ChunkedPath -> Response(Ok)
          .withEntity(Stream.emits("chunk".toSeq.map(_.toString)).covary[IO])
          .pure[IO],
        DelayedPath ->
          timer.sleep(1.second) *>
            Response(Ok).withEntity("delayed path").pure[IO],
        NoContentPath -> Response(NoContent).pure[IO],
        NotFoundPath -> Response(NotFound).withEntity("not found").pure[IO],
        EmptyNotFoundPath -> Response(NotFound).pure[IO],
        InternalServerErrorPath -> Response(InternalServerError).pure[IO]
      ))(_.unsafeRunSync())
}
