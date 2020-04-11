package org.http4s

package object syntax {
  object all extends AllSyntaxBinCompat
  object kleisli extends KleisliSyntax
  object literals extends LiteralsSyntax
  object string extends StringSyntax
}
