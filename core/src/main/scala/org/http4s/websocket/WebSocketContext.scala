package org.http4s.websocket

import cats.effect.IO
import org.http4s.{Headers, Response}

final case class WebSocketContext(
    webSocket: WebSocket,
    headers: Headers,
    failureResponse: IO[Response])
