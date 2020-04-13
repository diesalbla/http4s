---
menu: main
title: CSRF
weight: 123
---

Http4s provides [Middleware], named `CSRF`, to prevent Cross-site request forgery attacks. This middleware
is modeled after the [double submit cookie pattern](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#double-submit-cookie).

Examples in this document have the following dependencies.

```scala
libraryDependencies ++= Seq(
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-server" % http4sVersion
)
```

And we need some imports.

```tut:silent
  import cats.effect._
  import org.http4s._
  import org.http4s.dsl.io._
  import org.http4s.implicits._
  import org.http4s.headers.Referer
  import org.http4s.server.middleware._
```

Let's start by making a simple service.

```tut:book
val service = HttpRoutes.of[IO] {
  case _ =>
    Ok()
} 

val request = Request(Method.GET, uri"/")

service.orNotFound(request).unsafeRunSync
```

That didn't do all that much. Lets build out our CSRF Middleware by creating a `CSRFBuilder`

```tut:silent
val cookieName = "csrf-token"
val key  = CSRF.generateSigningKey[IO].unsafeRunSync
val defaultOriginCheck: Request => Boolean =
  CSRF.defaultOriginCheck[IO](_, "localhost", Uri.Scheme.http, None)
val csrfBuilder = CSRF[IO,IO](key, defaultOriginCheck)
```

More info on what is possible in the [CSRFBuilder] Docs,
but we will create a fairly simple CSRF Middleware in our example.

```tut:book
val csrf = csrfBuilder.withCookieName(cookieName).withCookieDomain(Some("localhost")).withCookiePath(Some("/")).build
```

Now we need to wrap this around our service! We're gonna start with a safe call
```tut:book
val dummyRequest: Request =
    Request(method = Method.GET).putHeaders(Header("Origin", "http://localhost"))
val resp = csrf.validate()(service.orNotFound)(dummyRequest).unsafeRunSync()
```
Notice how the response has the CSRF cookies added. How easy was
that? And, as described in [Middleware], services and middleware can be
composed such that only some of your endpoints are CSRF enabled. By default, 
safe methods will update the CSRF token, while unsafe methods will validate them.

Without getting too deep into it, safe methods are OPTIONS, GET, and HEAD. While unsafe methods are 
POST, PUT, PATCH, DELETE, and TRACE. To put it simply, state changing methods are unsafe. For more information,
check out this cheat sheet on [CSRF Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross-Site_Request_Forgery_Prevention_Cheat_Sheet.html#double-submit-cookie).

Unsafe requests (like POST) require us to send the CSRF token in the `X-Csrf-Token`
header (this is the default name, but it can be changed), so we are going to get the value
and send it up in our POST. I've also added the response cookie as a RequestCookie, normally
the browser would send this up with our request, but I needed to do it manually for the purpose of this demo.
```tut:book
val cookie = resp.cookies.head
val dummyPostRequest: Request =
    Request(method = Method.POST).putHeaders(
      Header("Origin", "http://localhost"),
      Header("X-Csrf-Token", cookie.content)
    ).addCookie(RequestCookie(cookie.name,cookie.content))
val resp = csrf.validate()(service.orNotFound)(dummyPostRequest).unsafeRunSync()
```

[Middleware]: ../middleware
[CSRFBuilder]: ../api/org/http4s/server/middleware/csrf$$csrfbuilder
