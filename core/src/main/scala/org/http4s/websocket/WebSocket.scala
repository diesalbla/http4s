package org.http4s.websocket

import fs2._
import cats.effect.IO

private[http4s] final case class WebSocket(
    send: Stream[IO, WebSocketFrame],
    receive: Pipe[IO, WebSocketFrame, Unit],
    onClose: IO[Unit]
)