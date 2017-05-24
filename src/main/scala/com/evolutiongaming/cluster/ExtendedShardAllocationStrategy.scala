package com.evolutiongaming.cluster

import akka.actor.{ActorRef, ActorSystem, Address}
import akka.cluster.sharding.ShardCoordinator.ShardAllocationStrategy
import akka.cluster.sharding.ShardRegion
import com.typesafe.scalalogging.LazyLogging

import scala.collection.immutable
import scala.concurrent.{ExecutionContext, Future}

abstract class ExtendedShardAllocationStrategy(
  implicit system: ActorSystem,
  ec: ExecutionContext) extends ShardAllocationStrategy with LazyLogging {

  protected def nodesToDeallocate: () => Set[Address]

  protected val addressHelper = AddressHelperExtension(system)

  protected def maxSimultaneousRebalance: Int

  protected def doRebalance(
    currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardRegion.ShardId]],
    rebalanceInProgress: Set[ShardRegion.ShardId]): Future[Set[ShardRegion.ShardId]]

  final def rebalance(
    currentShardAllocations: Map[ActorRef, immutable.IndexedSeq[ShardRegion.ShardId]],
    rebalanceInProgress: Set[ShardRegion.ShardId]): Future[Set[ShardRegion.ShardId]] = {

    def limitRebalance(f: => Set[ShardRegion.ShardId]): Set[ShardRegion.ShardId] =
      if (rebalanceInProgress.size >= maxSimultaneousRebalance) Set.empty
      else f take maxSimultaneousRebalance - rebalanceInProgress.size

    val nodesToForcedDeallocation = nodesToDeallocate()

    val shardsToRebalance: Future[Set[ShardRegion.ShardId]] =
      if (nodesToForcedDeallocation.isEmpty) {
        doRebalance(currentShardAllocations, rebalanceInProgress)
      } else {
        val shardsToForcedDeallocation = (for {
          (k, v) <- currentShardAllocations if nodesToForcedDeallocation contains addressHelper.toGlobal(k.path.address)
        } yield v).flatten.toSet -- rebalanceInProgress

        for {
          doRebalanceResult <- doRebalance(currentShardAllocations, rebalanceInProgress -- shardsToForcedDeallocation)
        } yield shardsToForcedDeallocation ++ doRebalanceResult
      }

    val result = for {
      shardsToRebalance <- shardsToRebalance
    } yield {
      val result = limitRebalance(shardsToRebalance)
      logger debug s"Nodes to forcefully deallocate: $nodesToForcedDeallocation"
      logger debug s"Final rebalance result: $result"
      result
    }

    result
  }
}