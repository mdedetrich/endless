package endless.example

import akka.actor.typed.ActorSystem
import akka.persistence.testkit.{
  PersistenceTestKitDurableStateStorePlugin,
  PersistenceTestKitPlugin
}
import cats.effect._
import com.typesafe.config.ConfigFactory

object Main extends IOApp {
  implicit val actorSystem: ActorSystem[Nothing] =
    ActorSystem.wrap(
      akka.actor.ActorSystem(
        "example-as",
        PersistenceTestKitPlugin.config
          .withFallback(PersistenceTestKitDurableStateStorePlugin.config)
          .withFallback(ConfigFactory.defaultApplication)
          .resolve()
      )
    )

  def run(args: List[String]): IO[ExitCode] = {
    ExampleApp.apply
      .flatMap(_.use(_ => IO.fromFuture(IO(actorSystem.whenTerminated))))
      .as(ExitCode.Success)
  }

}
