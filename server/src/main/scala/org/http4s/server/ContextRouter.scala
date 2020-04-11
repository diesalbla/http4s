package org.http4s.server

import org.http4s.{ContextRequest, ContextRoutes}
import cats.effect.Sync
import cats.syntax.semigroupk._

object ContextRouter {

  /**
    * Defines an [[ContextRoutes]] based on list of mappings.
    * @see define
    */
  def apply[A](mappings: (String, ContextRoutes[A])*): ContextRoutes[A] =
    define(mappings: _*)(ContextRoutes.empty[A])

  /**
    * Defines an [[ContextRoutes]] based on list of mappings and
    * a default Service to be used when none in the list match incoming requests.
    *
    * The mappings are processed in descending order (longest first) of prefix length.
    */
  def define[A](mappings: (String, ContextRoutes[A, F])*)(default: ContextRoutes[A]): ContextRoutes[A] =
    mappings.sortBy(_._1.length).foldLeft(default) {
      case (acc, (prefix, routes)) =>
        val segments = Router.toSegments(prefix)
        if (segments.isEmpty) routes <+> acc
        else
          { req =>
            (
              if (Router.toSegments(req.req.pathInfo).startsWith(segments))
                routes
                  .local[ContextRequest[A]](r =>
                    ContextRequest(r.context, Router.translate(prefix)(r.req))) <+> acc
              else
                acc
            )(req)
          }
    }
}
