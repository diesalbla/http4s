package org.http4s

import org.specs2.Specification
import org.typelevel.discipline.specs2.Discipline
import cats._
import cats.implicits._
import cats.laws.discipline.NonEmptyTraverseTests
import org.http4s.laws.discipline.arbitrary._

class ContextRequestSpec extends Specification with Discipline {
  implicit def nonBodyEquality[F[_], A: Eq]: Eq[ContextRequest[A]] =
    Eq.instance {
      case (first, second) =>
        first.context === second.context &&
          first.req === second.req
    }

  def is =
    checkAll(
      "ContextRequest[*]",
      NonEmptyTraverseTests[ContextRequest[Option, *]]
        .nonEmptyTraverse[Option, Int, Int, Int, Int, Option, Option]
    )
}
