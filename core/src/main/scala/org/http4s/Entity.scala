package org.http4s

import cats.Monoid
import cats.implicits._

final case class Entity(body: EntityBody, length: Option[Long] = None)

object Entity {
  implicit def http4sMonoidForEntity: Monoid[Entity] =
    new Monoid[Entity] {
      def combine(a1: Entity, a2: Entity): Entity =
        Entity(a1.body ++ a2.body, (a1.length, a2.length).mapN(_ + _))
      val empty: Entity = Entity.empty
    }

  val empty: Entity = Entity(EmptyBody, Some(0L))
}
