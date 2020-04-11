package org.http4s
package syntax

abstract class AllSyntaxBinCompat
    extends AllSyntax

trait AllSyntax
    extends AnyRef
    with KleisliSyntax
    with StringSyntax
    with LiteralsSyntax
