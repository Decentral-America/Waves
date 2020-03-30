package com.wavesplatform.state

import cats.implicits._
import com.wavesplatform.block.Block
import com.wavesplatform.block.Block.BlockId
import com.wavesplatform.common.state.ByteStr
import com.wavesplatform.consensus.PoSSelector
import com.wavesplatform.features.FeatureProvider._
import com.wavesplatform.lang.ValidationError
import com.wavesplatform.metrics._
import com.wavesplatform.mining._
import com.wavesplatform.network._
import com.wavesplatform.transaction.TxValidationError.{BlockAppendError, BlockFromFuture, GenericError}
import com.wavesplatform.transaction._
import com.wavesplatform.utils.{ScorexLogging, Time}
import com.wavesplatform.utx.UtxPoolImpl
import io.netty.channel.Channel
import kamon.Kamon
import monix.eval.Task

import scala.util.{Left, Right}

package object appender extends ScorexLogging {

  private val MaxTimeDrift: Long = 100 // millis

  // Invalid blocks, that are already in blockchain
  private val exceptions = List(
    812608 -> ByteStr.decodeBase58("2GNCYVy7k3kEPXzz12saMtRDeXFKr8cymVsG8Yxx3sZZ75eHj9csfXnGHuuJe7XawbcwjKdifUrV1uMq4ZNCWPf1").get,
    813207 -> ByteStr.decodeBase58("5uZoDnRKeWZV9Thu2nvJVZ5dBvPB7k2gvpzFD618FMXCbBVBMN2rRyvKBZBhAGnGdgeh2LXEeSr9bJqruJxngsE7").get
  )

  private[appender] def processAndBlacklistOnFailure[A, B](
      ch: Channel,
      peerDatabase: PeerDatabase,
      miner: Miner,
      start: => String,
      success: => String,
      errorPrefix: String
  )(f: => Task[Either[B, Option[BigInt]]]): Task[Either[B, Option[BigInt]]] = {
    log.debug(start)
    f map {
      case Right(maybeNewScore) =>
        log.debug(success)
        maybeNewScore.foreach(_ => miner.scheduleMining())
        Right(maybeNewScore)
      case Left(ve) =>
        log.warn(s"$errorPrefix: $ve")
        peerDatabase.blacklistAndClose(ch, s"$errorPrefix: $ve")
        Left(ve)
    }
  }

  private[appender] def appendBlock(
      blockchainUpdater: BlockchainUpdater,
      utxStorage: UtxPoolImpl,
      pos: PoSSelector,
      time: Time,
      verify: Boolean
  )(block: Block): Either[ValidationError, Option[Int]] = {
    if (verify)
      validateAndAppendBlock(blockchainUpdater, utxStorage, pos, time)(block)
    else
      pos
        .validateGenerationSignature(block)
        .flatMap(hitSource => appendBlock(blockchainUpdater, utxStorage, verify = false)(block, hitSource))
  }

  private[appender] def validateAndAppendBlock(
      blockchainUpdater: BlockchainUpdater,
      utxStorage: UtxPoolImpl,
      pos: PoSSelector,
      time: Time
  )(block: Block): Either[ValidationError, Option[Int]] = {
    val currentBlockchain = blockchainUpdater.blockchain(block.header.reference)
    for {
      _ <- Either.cond(
        !currentBlockchain.hasAccountScript(block.sender),
        (),
        BlockAppendError(s"Account(${block.sender.toAddress}) is scripted are therefore not allowed to forge blocks", block)
      )
      hitSource <- blockConsensusValidation(currentBlockchain, pos, time.correctedTime(), block) { (height, _) =>
        val balance = currentBlockchain.generatingBalance(block.sender)
        Either.cond(
          currentBlockchain.isEffectiveBalanceValid(height, block, balance),
          balance,
          s"generator's effective balance $balance is less that required for generation"
        )
      }
      baseHeight <- appendBlock(blockchainUpdater, utxStorage, verify = true)(block, hitSource)
    } yield baseHeight
  }

  private def appendBlock(blockchainUpdater: BlockchainUpdater, utxStorage: UtxPoolImpl, verify: Boolean)(
      block: Block,
      hitSource: ByteStr
  ): Either[ValidationError, Option[Int]] =
    metrics.appendBlock.measureSuccessful(blockchainUpdater.processBlock(block, hitSource, verify)).map { maybeDiscardedTxs =>
      metrics.utxRemoveAll.measure(utxStorage.removeAll(block.transactionData))
      maybeDiscardedTxs.map { discarded =>
        metrics.utxDiscardedPut.measure(utxStorage.addAndCleanup(discarded))
        blockchainUpdater.blockchain.height
      }
    }

  private def blockConsensusValidation(blockchain: Blockchain, pos: PoSSelector, currentTs: Long, block: Block)(
      genBalance: (Int, BlockId) => Either[String, Long]
  ): Either[ValidationError, ByteStr] =
    metrics.blockConsensusValidation
      .measureSuccessful {

        val blockTime = block.header.timestamp

        for {
          parentBlockHeight <- blockchain
            .heightOf(block.header.reference)
            .toRight(GenericError(s"height: history does not contain parent ${block.header.reference}"))
          parent <- blockchain.parentHeader(block.header).toRight(GenericError(s"parent: history does not contain parent ${block.header.reference}"))
          grandParent = blockchain.parentHeader(parent, 2)
          effectiveBalance <- genBalance(parentBlockHeight, block.header.reference).left.map(GenericError(_))
          _                <- validateBlockVersion(parentBlockHeight, block, blockchain)
          _                <- Either.cond(blockTime - currentTs < MaxTimeDrift, (), BlockFromFuture(blockTime))
          _                <- pos.validateBaseTarget(parentBlockHeight, block, parent, grandParent)
          hitSource        <- pos.validateGenerationSignature(block)
          _                <- pos.validateBlockDelay(parentBlockHeight, block.header, parent, effectiveBalance).orElse(checkExceptions(parentBlockHeight, block))
        } yield hitSource
      }
      .left
      .map {
        case GenericError(x) => GenericError(s"Block $block is invalid: $x")
        case x               => x
      }

  private def checkExceptions(height: Int, block: Block): Either[ValidationError, Unit] =
    Either.cond(
      exceptions.contains((height, block.id())),
      (),
      GenericError(s"Block time ${block.header.timestamp} less than expected")
    )

  private def validateBlockVersion(parentHeight: Int, block: Block, blockchain: Blockchain): Either[ValidationError, Unit] =
    Either.cond(
      blockchain.blockVersionAt(parentHeight + 1) == block.header.version,
      (),
      GenericError(s"Block version should be equal to ${blockchain.blockVersionAt(parentHeight + 1)}")
    )

  private[this] object metrics {
    val blockConsensusValidation = Kamon.timer("block-appender.block-consensus-validation")
    val appendBlock              = Kamon.timer("block-appender.blockchain-append-block")
    val utxRemoveAll             = Kamon.timer("block-appender.utx-remove-all")
    val utxDiscardedPut          = Kamon.timer("block-appender.utx-discarded-put")
  }

}
