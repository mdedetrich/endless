package endless.example.protocol

import endless.core.protocol.{Decoder, IncomingCommand, OutgoingCommand}
import endless.example.algebra.VehicleAlg
import endless.example.data.{LatLon, Speed}
import endless.example.proto.vehicle.commands.VehicleCommand.Command
import endless.example.proto.vehicle.commands._
import endless.example.proto.vehicle.models.{LatLonV1, SpeedV1}
import endless.example.proto.vehicle.replies.{
  GetPositionReply,
  GetRecoveryCountReply,
  GetSpeedReply,
  UnitReply
}
import endless.example.protocol.VehicleCommandProtocol.UnexpectedCommandException
import endless.protobuf.{ProtobufCommandProtocol, ProtobufDecoder}

class VehicleCommandProtocol extends ProtobufCommandProtocol[VehicleAlg] {
  def server[F[_]]: Decoder[IncomingCommand[F, VehicleAlg]] =
    ProtobufDecoder[VehicleCommand].map(_.command match {
      case Command.Empty => throw new UnexpectedCommandException
      case Command.SetSpeedV1(value) =>
        incomingCommand[F, UnitReply, Unit](
          _.setSpeed(Speed(value.speed.metersPerSecond)),
          _ => UnitReply()
        )
      case Command.SetPositionV1(value) =>
        incomingCommand[F, UnitReply, Unit](
          _.setPosition(LatLon(value.position.lat, value.position.lon)),
          _ => UnitReply()
        )
      case Command.GetSpeedV1(_) =>
        incomingCommand[F, GetSpeedReply, Option[Speed]](
          _.getSpeed,
          maybeSpeed => GetSpeedReply.of(maybeSpeed.map(speed => SpeedV1.of(speed.metersPerSecond)))
        )
      case Command.GetPositionV1(_) =>
        incomingCommand[F, GetPositionReply, Option[LatLon]](
          _.getPosition,
          maybePosition =>
            GetPositionReply.of(
              maybePosition.map(position => LatLonV1.of(position.lat, position.lon))
            )
        )
      case Command.GetRecoveryCountV1(_) =>
        incomingCommand[F, GetRecoveryCountReply, Int](
          _.getRecoveryCount,
          recoveryCount => GetRecoveryCountReply.of(recoveryCount)
        )
      case Command.IncrementRecoveryCountV1(_) =>
        incomingCommand[F, UnitReply, Unit](_.incrementRecoveryCount, _ => UnitReply())
    })

  def client: VehicleAlg[OutgoingCommand[*]] = new VehicleAlg[OutgoingCommand] {
    def setSpeed(speed: Speed): OutgoingCommand[Unit] =
      outgoingCommand[VehicleCommand, UnitReply, Unit](
        VehicleCommand.of(
          Command.SetSpeedV1(SetSpeedV1.of(SpeedV1.of(speed.metersPerSecond)))
        ),
        _ => ()
      )

    def setPosition(position: LatLon): OutgoingCommand[Unit] =
      outgoingCommand[VehicleCommand, UnitReply, Unit](
        VehicleCommand.of(
          Command.SetPositionV1(SetPositionV1.of(LatLonV1.of(position.lat, position.lon)))
        ),
        _ => ()
      )

    def getSpeed: OutgoingCommand[Option[Speed]] =
      outgoingCommand[VehicleCommand, GetSpeedReply, Option[Speed]](
        VehicleCommand.of(Command.GetSpeedV1(GetSpeedV1())),
        _.speed.map(speed => Speed(speed.metersPerSecond))
      )

    def getPosition: OutgoingCommand[Option[LatLon]] =
      outgoingCommand[VehicleCommand, GetPositionReply, Option[LatLon]](
        VehicleCommand.of(Command.GetPositionV1(GetPositionV1())),
        _.position.map(position => LatLon(position.lat, position.lon))
      )

    def getRecoveryCount: OutgoingCommand[Int] =
      outgoingCommand[VehicleCommand, GetRecoveryCountReply, Int](
        VehicleCommand.of(Command.GetRecoveryCountV1(GetRecoveryCountV1())),
        _.recoveryCount
      )

    def incrementRecoveryCount: OutgoingCommand[Unit] =
      outgoingCommand[VehicleCommand, UnitReply, Unit](
        VehicleCommand.of(Command.IncrementRecoveryCountV1(IncrementRecoveryCountV1())),
        _ => ()
      )
  }
}

object VehicleCommandProtocol {
  final class UnexpectedCommandException extends RuntimeException("Unexpected command")
}
