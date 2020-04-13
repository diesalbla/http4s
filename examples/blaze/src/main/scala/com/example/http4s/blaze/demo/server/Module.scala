package com.example.http4s.blaze.demo.server

import cats.data.OptionT
import cats.effect._
import cats.syntax.semigroupk._ // For <+>
import com.example.http4s.blaze.demo.server.endpoints._
import com.example.http4s.blaze.demo.server.endpoints.auth.{
  BasicAuthHttpEndpoint,
  GitHubHttpEndpoint
}
import com.example.http4s.blaze.demo.server.service.{FileService, GitHubService}
import org.http4s.HttpRoutes
import org.http4s.client.Client
import org.http4s.server.HttpMiddleware
import org.http4s.server.middleware.{AutoSlash, ChunkAggregator, GZip, Timeout}

import scala.concurrent.duration._

class Module[F[_]: ConcurrentEffect: ContextShift: Timer](client: Client[F], blocker: Blocker) {
  private val fileService = new FileService[F](blocker)

  private val gitHubService = new GitHubService[F](client)

  def middleware: HttpMiddleware[F] = { (routes: HttpRoutes) =>
    GZip(routes)
  }.compose(routes => AutoSlash(routes))

  val fileHttpEndpoint: HttpRoutes =
    new FileHttpEndpoint[F](fileService).service

  val nonStreamFileHttpEndpoint: HttpRoutes =
    ChunkAggregator(OptionT.liftK[F])(fileHttpEndpoint)

  private val hexNameHttpEndpoint: HttpRoutes =
    new HexNameHttpEndpoint[F].service

  private val compressedEndpoints: HttpRoutes =
    middleware(hexNameHttpEndpoint)

  private val timeoutHttpEndpoint: HttpRoutes =
    new TimeoutHttpEndpoint[F].service

  private val timeoutEndpoints: HttpRoutes =
    Timeout(1.second)(timeoutHttpEndpoint)

  private val mediaHttpEndpoint: HttpRoutes =
    new JsonXmlHttpEndpoint[F].service

  private val multipartHttpEndpoint: HttpRoutes =
    new MultipartHttpEndpoint[F](fileService).service

  private val gitHubHttpEndpoint: HttpRoutes =
    new GitHubHttpEndpoint[F](gitHubService).service

  val basicAuthHttpEndpoint: HttpRoutes =
    new BasicAuthHttpEndpoint[F].service

  val httpServices: HttpRoutes = (
    compressedEndpoints <+> timeoutEndpoints
      <+> mediaHttpEndpoint <+> multipartHttpEndpoint
      <+> gitHubHttpEndpoint
  )
}
