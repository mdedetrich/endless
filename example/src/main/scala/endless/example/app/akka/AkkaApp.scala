package endless.example.app.akka

import akka.actor.typed.ActorSystem
import akka.persistence.testkit.{
  PersistenceTestKitDurableStateStorePlugin,
  PersistenceTestKitPlugin
}
import akka.persistence.typed.{EventAdapter, EventSeq, SnapshotAdapter}
import akka.util.Timeout
import cats.effect._
import cats.syntax.applicative._
import com.typesafe.config.ConfigFactory
import endless.example.algebra._
import endless.example.app.HttpServer
import endless.example.app.impl.{Availabilities, Bookings, Vehicles}
import endless.example.data.Booking.BookingID
import endless.example.data.Vehicle.VehicleID
import endless.example.data._
import endless.example.logic._
import endless.runtime.akka.deploy.AkkaCluster
import endless.runtime.akka.syntax.deploy._
import org.http4s.server.Server
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

object AkkaApp extends Bookings with Vehicles with Availabilities {

  def apply(port: Int): Resource[IO, Server] = IO.executionContext.toResource
    .flatMap(actorSystem)
    .flatMap(createAkkaApp(port))

  private def actorSystem(executionContext: ExecutionContext): Resource[IO, ActorSystem[Nothing]] =
    Resource.make(
      IO(
        ActorSystem.wrap(
          akka.actor.ActorSystem(
            name = "example-akka-as",
            config = Some(
              PersistenceTestKitPlugin.config
                .withFallback(PersistenceTestKitDurableStateStorePlugin.config)
                .withFallback(ConfigFactory.defaultApplication)
                .resolve()
            ),
            defaultExecutionContext = Some(executionContext),
            classLoader = None
          )
        )
      )
    )(system =>
      IO.fromFuture(IO.blocking {
        system.terminate()
        system.whenTerminated
      }).void
    )

  // #main
  private def createAkkaApp(port: Int)(actorSystem: ActorSystem[Nothing]): Resource[IO, Server] = {
    implicit val askTimeout: Timeout = Timeout(10.seconds)

    Resource
      .eval(Slf4jLogger.create[IO])
      .flatMap { implicit logger: Logger[IO] =>
        AkkaCluster.managedResource[IO](actorSystem).flatMap { implicit cluster: AkkaCluster[IO] =>
          Resource
            .both(
              deployEntity[
                IO,
                Booking,
                BookingEvent,
                BookingID,
                BookingAlg,
                BookingRepositoryAlg
              ](
                BookingEntity(_),
                BookingRepository(_),
                { case (effector, _, _) => BookingEffector(effector) },
                customizeBehavior = (_, behavior) =>
                  behavior.eventAdapter(
                    new EventAdapter[
                      BookingEvent,
                      endless.example.proto.booking.events.BookingEvent
                    ] {
                      def toJournal(event: BookingEvent)
                          : endless.example.proto.booking.events.BookingEvent =
                        eventAdapter.toJournal(event)
                      def manifest(event: BookingEvent): String = event.getClass.getName
                      def fromJournal(
                          event: endless.example.proto.booking.events.BookingEvent,
                          manifest: String
                      ): EventSeq[BookingEvent] = EventSeq.single(eventAdapter.fromJournal(event))
                    }
                  )
              ),
              deployDurableEntityF[IO, Vehicle, VehicleID, VehicleAlg, VehicleRepositoryAlg](
                VehicleEntity(_).pure[IO],
                VehicleRepository(_).pure[IO],
                { case (effector, _, _) => VehicleEffector.apply[IO](effector).map(_.apply) },
                customizeBehavior = (_, behavior) =>
                  behavior.snapshotAdapter(new SnapshotAdapter[Option[Vehicle]] {
                    def toJournal(state: Option[Vehicle]): Any = stateAdapter.toJournal(state)
                    def fromJournal(from: Any): Option[Vehicle] = stateAdapter.fromJournal(from)
                  })
              )
            )
            .flatMap { case ((bookingRepository, _), (vehicleRepository, _)) =>
              HttpServer(port, bookingRepository, vehicleRepository, cluster.isMemberUp)
            }
        }
      }
  }
  // #main
}
