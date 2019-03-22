package com.martinsnyder.chatserver

import cats.effect._
import cats.implicits._
import fs2.Stream
import fs2.concurrent.{Queue, Topic}
import org.http4s.HttpRoutes
import org.http4s.server.Router
import org.http4s.server.blaze.BlazeServerBuilder
import org.http4s.syntax.kleisli._

/*
 * Top-level executor for our application
 */
object HelloWorldServer extends IOApp {
  def run(args: List[String]): IO[ExitCode] =
    for (
      // Synchronization objects must be created at the top-level so they can be shared across the application
      queue <- Queue.unbounded[IO, InputMessage];
      topic <- Topic[IO, OutputMessage](SendToUsers(Set.empty, ""));
      exitCode <- {
        // Stream for HTTP requests
        val httpStream = ServerStream.stream[IO](queue, topic)

        // Stream to process items from the queue and publish the results to the topic
        // Note mapAccumulate below which performs our state manipulation
        val processingStream =
          queue
            .dequeue
            .mapAccumulate(ChatState())((prevState, inputMsg) => prevState.process(inputMsg))
            .map(_._2)             // Strip our state object from the stream and propagate only our messages
            .flatMap(Stream.emits) // Lift our messages into a stream of their own
            .through(topic.publish)

        // fs2 Streams must be "pulled" to process messages. Drain will perpetually pull our top-level streams
        httpStream.concurrently(processingStream).compile.drain.as(ExitCode.Success)
      }

    ) yield exitCode
}

object ServerStream {
  def helloWorldRoutes[F[_]: Effect: ContextShift](queue: Queue[F, InputMessage], topic: Topic[F, OutputMessage]): HttpRoutes[F] =
    new ChatRoutes[F](queue, topic).routes

  // Builds a stream for HTTP events processed by our router
  def stream[F[_]: ConcurrentEffect: Timer: ContextShift](queue: Queue[F, InputMessage], topic: Topic[F, OutputMessage]): fs2.Stream[F, ExitCode]=
    BlazeServerBuilder[F]
      .bindHttp(8080, "0.0.0.0")
      .withHttpApp(Router(
        "/" -> helloWorldRoutes(queue, topic)
      ).orNotFound)
      .serve
}