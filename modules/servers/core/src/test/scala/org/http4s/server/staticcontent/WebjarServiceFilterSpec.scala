/*
 * Copyright 2013-2020 http4s.org
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package org.http4s.server.staticcontent

import cats.effect.IO
import org.http4s._
import org.http4s.Method.GET

class WebjarServiceFilterSpec extends Http4sSpec with StaticContentShared {

  def routes: HttpRoutes[IO] =
    webjarServiceBuilder[IO](testBlocker)
      .withWebjarAssetFilter(webjar =>
        webjar.library == "test-lib" && webjar.version == "1.0.0" && webjar.asset == "testresource.txt")
      .withBlocker(testBlocker)
      .toRoutes

  "The WebjarService" should {
    "Return a 200 Ok file" in {
      val req = Request[IO](GET, uri"/test-lib/1.0.0/testresource.txt")
      val rb = runReq(req)

      rb._1 must_== testWebjarResource
      rb._2.status must_== Status.Ok
    }

    "Not find filtered asset" in {
      val req = Request[IO](GET, uri"/test-lib/1.0.0/sub/testresource.txt")
      val rb = runReq(req)

      rb._2.status must_== Status.NotFound
    }
  }
}
