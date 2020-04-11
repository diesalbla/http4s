package org.http4s.dsl.impl

import org.http4s.{AuthedRequest, Request}

trait Auth {
  object as {
    def unapply[A](ar: AuthedRequest[A]): Option[(Request, A)] = Some(ar.req -> ar.context)
  }
}
