/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 *
 * Based on https://github.com/spray/spray/blob/v1.1-M7/spray-http/src/main/scala/spray/http/parser/CacheControlHeader.scala
 * Copyright (C) 2011-2012 spray.io
 * Based on code copyright (C) 2010-2011 by the BlueEyes Web Framework Team
 */

package org.http4s
package parser

import java.util.concurrent.TimeUnit
import org.http4s.headers.`Cache-Control`
import org.http4s.internal.parboiled2.{ParserInput, Rule1}
import org.http4s.CacheDirective._
import org.typelevel.ci.CIString
import scala.concurrent.duration._

private[parser] trait CacheControlHeader {
  def CACHE_CONTROL(value: String): ParseResult[`Cache-Control`] =
    new CacheControlParser(value).parse

  private class CacheControlParser(input: ParserInput)
      extends Http4sHeaderParser[`Cache-Control`](input) {
    def entry: Rule1[`Cache-Control`] =
      rule {
        oneOrMore(CacheDirective).separatedBy(ListSep) ~ EOI ~> { (xs: Seq[CacheDirective]) =>
          `Cache-Control`(xs.head, xs.tail: _*)
        }
      }

    def CacheDirective: Rule1[CacheDirective] =
      rule {
        ("no-cache" ~ optional("=" ~ FieldNames)) ~> (fn =>
          `no-cache`(fn.map(_.map(CIString(_)).toList).getOrElse(Nil))) |
          "no-store" ~ push(`no-store`) |
          "no-transform" ~ push(`no-transform`) |
          "max-age=" ~ DeltaSeconds ~> (s => `max-age`(s)) |
          "max-stale" ~ optional("=" ~ DeltaSeconds) ~> (s => `max-stale`(s)) |
          "min-fresh=" ~ DeltaSeconds ~> (s => `min-fresh`(s)) |
          "only-if-cached" ~ push(`only-if-cached`) |
          "public" ~ push(`public`) |
          "private" ~ optional("=" ~ FieldNames) ~> (fn =>
            `private`(fn.map(_.map(CIString(_)).toList).getOrElse(Nil))) |
          "must-revalidate" ~ push(`must-revalidate`) |
          "proxy-revalidate" ~ push(`proxy-revalidate`) |
          "s-maxage=" ~ DeltaSeconds ~> (s => `s-maxage`(s)) |
          "stale-if-error=" ~ DeltaSeconds ~> (s => `stale-if-error`(s)) |
          "stale-while-revalidate=" ~ DeltaSeconds ~> (s => `stale-while-revalidate`(s)) |
          (Token ~ optional("=" ~ (Token | QuotedString)) ~> {
            (name: String, arg: Option[String]) =>
              org.http4s.CacheDirective(CIString(name), arg)
          })
      }

    def FieldNames: Rule1[collection.Seq[String]] =
      rule {
        oneOrMore(QuotedString).separatedBy(ListSep)
      }
    def DeltaSeconds: Rule1[Duration] =
      rule {
        capture(oneOrMore(Digit)) ~> { (s: String) =>
          Duration(s.toLong, TimeUnit.SECONDS)
        }
      }
  }
}
