package endless.example.logic

import cats.data.EitherT
import cats.effect.kernel.Clock
import cats.syntax.applicative._
import cats.syntax.eq._
import cats.syntax.flatMap._
import cats.syntax.show._
import endless.\/
import endless.core.entity.Entity
import endless.example.algebra.BookingAlg
import endless.example.algebra.BookingAlg.{BookingAlreadyExists, BookingUnknown, CancelError}
import endless.example.data.Booking._
import endless.example.data.BookingEvent._
import endless.example.data.{Booking, BookingEvent, LatLon}
import org.typelevel.log4cats.Logger

import java.time.Instant

//#definition
final case class BookingEntity[F[_]: Logger: Clock](entity: Entity[F, Booking, BookingEvent])
    extends BookingAlg[F] {
  import entity._

  def place(
      bookingID: BookingID,
      time: Instant,
      passengerCount: Int,
      origin: LatLon,
      destination: LatLon
  ): F[BookingAlreadyExists \/ Unit] =
    ifUnknownF(
      Logger[F].info(show"Creating booking with ID $bookingID") >> write(
        BookingPlaced(bookingID, time, origin, destination, passengerCount)
      )
    )(_ => BookingAlreadyExists(bookingID))

  def get: F[BookingUnknown.type \/ Booking] = ifKnown(identity)(BookingUnknown)

  def changeOrigin(newOrigin: LatLon): F[BookingUnknown.type \/ Unit] =
    ifKnownF(booking =>
      if (booking.origin =!= newOrigin) entity.write(OriginChanged(newOrigin)) else ().pure
    )(BookingUnknown)

  def changeDestination(newDestination: LatLon): F[BookingUnknown.type \/ Unit] =
    ifKnownF(booking =>
      if (booking.destination =!= newDestination) entity.write(DestinationChanged(newDestination))
      else ().pure
    )(BookingUnknown)

  def changeOriginAndDestination(
      newOrigin: LatLon,
      newDestination: LatLon
  ): F[BookingUnknown.type \/ Unit] = changeOrigin(newOrigin) >> changeDestination(newDestination)

  def cancel: F[CancelError \/ Unit] =
    ifKnownT[CancelError, Unit](booking =>
      booking.status match {
        case Status.Accepted | Status.Pending =>
          EitherT.liftF(
            (Clock[F].realTimeInstant >>= (timestamp =>
              Logger[F]
                .info(show"Cancelling booking with ID ${booking.id} at ${timestamp.toString}")
            )) >> entity.write(BookingCancelled)
          )
        case Status.Cancelled => EitherT.pure(())
        case Status.Rejected  => EitherT.leftT[F, Unit](BookingAlg.BookingWasRejected(booking.id))
      }
    )(
      BookingUnknown
    )

  def notifyCapacity(isAvailable: Boolean): F[BookingAlg.BookingUnknown.type \/ Unit] =
    ifKnownF(_.status match {
      case Status.Pending =>
        if (isAvailable) entity.write(BookingAccepted) else entity.write(BookingRejected)
      case _ => ().pure
    })(
      BookingUnknown
    )
}
//#definition
