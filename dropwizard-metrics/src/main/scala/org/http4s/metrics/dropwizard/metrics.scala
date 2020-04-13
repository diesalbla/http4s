package org.http4s
package metrics

import cats.Applicative
import cats.effect.Sync
import cats.syntax.applicative._
import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.json.MetricsModule
import com.fasterxml.jackson.databind.ObjectMapper
import java.util.concurrent.TimeUnit

package object dropwizard {
  private val defaultMapper = {
    val module = new MetricsModule(TimeUnit.SECONDS, TimeUnit.SECONDS, true)
    new ObjectMapper().registerModule(module)
  }

  /** Encodes a metric registry in JSON format */
  def metricRegistryEncoder(mapper: ObjectMapper = defaultMapper): EntityEncoder[MetricRegistry] =
    EntityEncoder[String].contramap { metricRegistry =>
      val writer = mapper.writerWithDefaultPrettyPrinter()
      writer.writeValueAsString(metricRegistry)
    }

  /** Returns an OK response with a JSON dump of a MetricRegistry */
  def metricsResponse(registry: MetricRegistry, mapper: ObjectMapper = defaultMapper): IO[Response] = {
    implicit val encoder = metricRegistryEncoder[F](mapper)
    IO.pure(Response(Status.Ok).withEntity(registry))
  }

  /** Returns an OK response with a JSON dump of a MetricRegistry */
  def metricsService(registry: MetricRegistry, mapper: ObjectMapper = defaultMapper): HttpRoutes =
    HttpRoutes.of {
      case req if req.method == Method.GET => metricsResponse(registry, mapper)
    }
}
