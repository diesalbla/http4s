package org.http4s.internal

import cats.effect.{Resource, IO}
import fs2.Stream

private[http4s] trait BackendBuilder[A] {

  /** Returns the backend as a resource.  Resource acquire waits
    * until the backend is ready to process requests.
    */
  def resource: Resource[IO, A]

  /** Returns the backend as a single-element stream.  The stream
    * does not emit until the backend is ready to process requests.
    * The backend is shut down when the stream is finalized.
    */
  def stream: Stream[IO, A] = Stream.resource(resource)

  /** Returns an effect that allocates a backend and an `F[Unit]` to
    * release it.  The returned `F` waits until the backend is ready
    * to process requests.  The second element of the tuple shuts
    * down the backend when run.
    *
    * Unlike [[resource]] and [[stream]], there is no automatic
    * release of the backend.  This function is intended for REPL
    * sessions, tests, and other situations where composing a
    * [[cats.effect.Resource]] or [[fs2.Stream]] is not tenable.
    * [[resource]] or [[stream]] is recommended wherever possible.
    */
  def allocated: IO[(A, IO[Unit])] = resource.allocated
}
