package endless.runtime.akka.deploy

import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.cluster.sharding.typed.ShardingEnvelope
import akka.cluster.sharding.typed.scaladsl.{ClusterSharding, EntityContext}
import akka.persistence.typed.state.scaladsl.DurableStateBehavior
import akka.util.Timeout
import cats.effect.kernel.{Async, Resource}
import cats.syntax.applicative._
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.tagless.FunctorK
import endless.core.entity._
import endless.core.interpret.DurableEntityT.DurableEntityT
import endless.core.interpret.EffectorT._
import endless.core.interpret._
import endless.core.protocol.{CommandProtocol, CommandRouter, EntityIDCodec}
import endless.runtime.akka.ShardingCommandRouter
import endless.runtime.akka.data._
import endless.runtime.akka.deploy.DurableDeployer.EffectorParameters
import endless.runtime.akka.deploy.internal.DurableShardedEntityDeployer
import org.typelevel.log4cats.Logger

trait DurableDeployer {

  /** This function brings everything together and delivers a `Resource` with the repository
    * instance in context `F` bundled with the ref to the shard region actor returned by the call to
    * `ClusterSharding`.
    *
    * The function is parameterized with the context `F` and the various involved types: `S` for
    * entity state, `ID` for entity ID and `Alg` & `RepositoryAlg` for entity and repository
    * algebras respectively (both higher-kinded type constructors).
    *
    * In order to bridge Akka's implicit asynchronicity with the side-effect free context `F` used
    * for algebras, it requires `Async` from `F`. This makes it possible to use the `Dispatcher`
    * mechanism for running the command handling monadic chain "seemingly" synchronously (but in
    * fact submitting for execution and blocking for completion from within a thread managed by
    * akka).
    *
    * `Logger` is also required as the library supports some basic logging capabilities. Entity
    * algebra `Alg` must also be equipped with an instance of `FunctorK` to support natural
    * transformations.
    *
    * First parameters of the function are constructor functions for instances of entity &
    * repository algebras and effector, implicitly interpreted with their respective monad
    * transformers. An optional behavior customization hook is also provided for client code to
    * configure aspects such as recovery, etc.
    *
    * All remaining typeclass instances for entity operation are pulled from implicit scope: entity
    * name provider, entity ID encoder, command protocol function in addition to the usual akka ask
    * timeout, actor system and cluster sharding extension.
    *
    * Although its signature looks complicated, in practice usage of this method isn't difficult
    * with the proper implicits and definitions: refer to the sample application for example usage:
    *
    * \```scala deployDurableEntity[IO, Vehicle, VehicleID, VehicleAlg,
    * VehicleRepositoryAlg](VehicleEntity(_), VehicleRepository(_), VehicleEffector(_) )```
    *
    * '''Important''': `deployDurableEntity` needs to be called upon application startup, before
    * joining the cluster as the `ClusterSharding` extension needs to know about the various entity
    * types beforehand.
    *
    * @param createEntity
    *   creator for entity algebra accepting an instance of `DurableEntity`, interpreted with
    *   `DurableEntityT`
    * @param createRepository
    *   creator for repository algebra accepting an instance of `Repository`
    * @param createEffector
    *   creator for effector accepting an instance of `Effector`, repository (for scenarios where
    *   effector processes require access to the repository itself) and entity, interpreted with
    *   `EffectorT` (you can pass in `_ => EffectorT.unit` for unit effector)
    * @param customizeBehavior
    *   hook to further customize Akka `DurableStateBehavior`. By default the behavior enforces
    *   replies, and is configured with the command handler. It also triggers the effector upon
    *   successful recovery as well as logs in warning upon recovery failure.
    * @param sharding
    *   Akka cluster sharding extension
    * @param actorSystem
    *   actor system
    * @param nameProvider
    *   entity name provider
    * @param commandProtocol
    *   instance of command protocol for the algebra (serialization specification)
    * @param askTimeout
    *   Akka ask timeout
    * @tparam F
    *   context, requires instances of `Async` and `Logger`
    * @tparam S
    *   state
    * @tparam ID
    *   entity ID, requires an instance of `EntityIDCodec`
    * @tparam Alg
    *   entity algebra, requires an instance of `FunctorK`
    * @tparam RepositoryAlg
    *   repository algebra
    * @return
    *   resource (with underlying allocated dispatcher) containing the algebra in `F` context to
    *   interact with the entity together with Akka shard region actor ref
    */
  def deployDurableEntity[F[_]: Async: Logger, S, ID: EntityIDCodec, Alg[
      _[_]
  ]: FunctorK, RepositoryAlg[
      _[_]
  ]](
      createEntity: DurableEntity[DurableEntityT[F, S, *], S] => Alg[DurableEntityT[F, S, *]],
      createRepository: Repository[F, ID, Alg] => RepositoryAlg[F],
      createEffector: EffectorParameters[F, S, Alg, RepositoryAlg] => EffectorT[F, S, Alg, Unit],
      customizeBehavior: (
          EntityContext[Command],
          DurableStateBehavior[Command, Option[S]]
      ) => Behavior[Command] =
        (_: EntityContext[Command], behavior: DurableStateBehavior[Command, Option[S]]) => behavior,
      customizeEntity: akka.cluster.sharding.typed.scaladsl.Entity[Command, ShardingEnvelope[
        Command
      ]] => akka.cluster.sharding.typed.scaladsl.Entity[Command, ShardingEnvelope[Command]] =
        identity
  )(implicit
      sharding: ClusterSharding,
      actorSystem: ActorSystem[_],
      nameProvider: EntityNameProvider[ID],
      commandProtocol: CommandProtocol[Alg],
      askTimeout: Timeout
  ): Resource[F, (RepositoryAlg[F], ActorRef[ShardingEnvelope[Command]])] =
    deployDurableEntityF(
      (entity: DurableEntity[DurableEntityT[F, S, *], S]) => createEntity(entity).pure[F],
      (repository: Repository[F, ID, Alg]) => createRepository(repository).pure[F],
      (parameters: EffectorParameters[F, S, Alg, RepositoryAlg]) =>
        createEffector(parameters).pure[F],
      customizeBehavior,
      customizeEntity
    )

  /** Overload of [[deployDurableEntity]] that accepts creation functions expressed in `F` context.
    * This is particularly useful for creating a stateful effector (which is specific to an entity).
    */
  def deployDurableEntityF[F[_]: Async: Logger, S, ID: EntityIDCodec, Alg[
      _[_]
  ]: FunctorK, RepositoryAlg[
      _[_]
  ]](
      createEntity: DurableEntity[DurableEntityT[F, S, *], S] => F[Alg[DurableEntityT[F, S, *]]],
      createRepository: Repository[F, ID, Alg] => F[RepositoryAlg[F]],
      createEffector: EffectorParameters[F, S, Alg, RepositoryAlg] => F[EffectorT[F, S, Alg, Unit]],
      customizeBehavior: (
          EntityContext[Command],
          DurableStateBehavior[Command, Option[S]]
      ) => Behavior[Command] =
        (_: EntityContext[Command], behavior: DurableStateBehavior[Command, Option[S]]) => behavior,
      customizeEntity: akka.cluster.sharding.typed.scaladsl.Entity[Command, ShardingEnvelope[
        Command
      ]] => akka.cluster.sharding.typed.scaladsl.Entity[Command, ShardingEnvelope[Command]] =
        identity
  )(implicit
      sharding: ClusterSharding,
      actorSystem: ActorSystem[_],
      nameProvider: EntityNameProvider[ID],
      commandProtocol: CommandProtocol[Alg],
      askTimeout: Timeout
  ): Resource[F, (RepositoryAlg[F], ActorRef[ShardingEnvelope[Command]])] = {
    implicit val commandRouter: CommandRouter[F, ID] = ShardingCommandRouter.apply
    val repositoryT = RepositoryT.apply[F, ID, Alg]
    for {
      interpretedEntityAlg <- Resource.eval(createEntity(DurableEntityT.instance))
      repository <- Resource.eval(createRepository(repositoryT))
      entity <- new DurableShardedEntityDeployer(
        interpretedEntityAlg,
        repository,
        repositoryT,
        createEffector,
        customizeBehavior
      ).deployShardedEntity(sharding, customizeEntity).map((repository, _))
    } yield entity
  }

}

object DurableDeployer {
  type EffectorParameters[F[_], S, Alg[_[_]], RepositoryAlg[_[_]]] =
    (Effector[EffectorT[F, S, Alg, *], S, Alg], RepositoryAlg[F], Alg[F])
}