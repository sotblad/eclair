/*
 * Copyright 2020 ACINQ SAS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fr.acinq.eclair

import java.io.File
import java.util.concurrent.atomic.{AtomicLong, AtomicReference}

import akka.actor.{Actor, ActorLogging, ActorSystem, Props, ReceiveTimeout, SupervisorStrategy}
import com.softwaremill.sttp.okhttp.OkHttpFutureBackend
import com.typesafe.config.{Config, ConfigFactory}
import fr.acinq.eclair.blockchain.EclairWallet
import fr.acinq.eclair.blockchain.fee.{FeeEstimator, FeeratesPerKB, FeeratesPerKw}
import fr.acinq.eclair.channel.Register
import fr.acinq.eclair.crypto.LocalKeyManager
import fr.acinq.eclair.db.{BackupHandler, Databases}
import fr.acinq.eclair.io.Switchboard
import fr.acinq.eclair.payment.receive.PaymentHandler
import fr.acinq.eclair.payment.relay.{CommandBuffer, Relayer}
import fr.acinq.eclair.payment.{Auditor, PaymentReceived}
import grizzled.slf4j.Logging
import scodec.bits.ByteVector

import scala.concurrent.{ExecutionContext, Future, Promise}

class ReceiveLiteSetup(datadir: File,
                       overrideDefaults: Config = ConfigFactory.empty(),
                       seed_opt: Option[ByteVector] = None,
                       db: Option[Databases] = None,
                       eclairWallet: EclairWallet)(implicit system: ActorSystem) extends Logging {

  implicit val formats = org.json4s.DefaultFormats
  implicit val ec = ExecutionContext.Implicits.global
  implicit val sttpBackend = OkHttpFutureBackend()

  logger.info(s"hello!")
  logger.info(s"version=${Kit.getVersion} commit=${Kit.getCommit}")
  logger.info(s"datadir=${datadir.getCanonicalPath}")


  datadir.mkdirs()
  val appConfig = NodeParams.loadConfiguration(datadir, overrideDefaults)
  val config = appConfig.getConfig("eclair")
  val seed = seed_opt.getOrElse(NodeParams.getSeed(datadir))
  val chain = config.getString("chain")
  val chaindir = new File(datadir, chain)
  val keyManager = new LocalKeyManager(seed, NodeParams.makeChainHash(chain))

  val database = db match {
    case Some(d) => d
    case None => Databases.sqliteJDBC(chaindir)
  }

  /**
   * This counter holds the current blockchain height.
   * It is mainly used to calculate htlc expiries.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val blockCount = new AtomicLong(0)

  /**
   * This holds the current feerates, in satoshi-per-kilobytes.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val feeratesPerKB = new AtomicReference[FeeratesPerKB](null)

  /**
   * This holds the current feerates, in satoshi-per-kw.
   * The value is read by all actors, hence it needs to be thread-safe.
   */
  val feeratesPerKw = new AtomicReference[FeeratesPerKw](null)

  val feeEstimator = new FeeEstimator {
    override def getFeeratePerKb(target: Int): Long = feeratesPerKB.get().feePerBlock(target)

    override def getFeeratePerKw(target: Int): Long = feeratesPerKw.get().feePerBlock(target)
  }

  val nodeParams = NodeParams.makeNodeParams(config, keyManager, None, database, blockCount, feeEstimator)

  logger.info(s"nodeid=${nodeParams.nodeId} alias=${nodeParams.alias}")
  logger.info(s"using chain=$chain chainHash=${nodeParams.chainHash}")

  def bootstrap: Future[Boolean] = {

    val pReceiveDone = Promise[Boolean]()
    system.actorOf(Props(new ReceiveListener(pReceiveDone)))

    nodeParams.db.channels.listLocalChannels().headOption match {
      case Some(channel) =>
        // we have a channel, default feerate will be the one in the current commitment
        // TODO: hack! we use the same fee for all block targets
        val feerates = FeeratesPerKw(
          block_1 = channel.commitments.localCommit.spec.feeratePerKw,
          blocks_2 = channel.commitments.localCommit.spec.feeratePerKw,
          blocks_6 = channel.commitments.localCommit.spec.feeratePerKw,
          blocks_12 = channel.commitments.localCommit.spec.feeratePerKw,
          blocks_36 = channel.commitments.localCommit.spec.feeratePerKw,
          blocks_72 = channel.commitments.localCommit.spec.feeratePerKw,
          blocks_144 = channel.commitments.localCommit.spec.feeratePerKw
        )
        feeratesPerKB.set(FeeratesPerKB(
          block_1 = feerateKw2KB(feerates.block_1),
          blocks_2 = feerateKw2KB(feerates.blocks_2),
          blocks_6 = feerateKw2KB(feerates.blocks_6),
          blocks_12 = feerateKw2KB(feerates.blocks_12),
          blocks_36 = feerateKw2KB(feerates.blocks_36),
          blocks_72 = feerateKw2KB(feerates.blocks_72),
          blocks_144 = feerateKw2KB(feerates.blocks_144)))
        feeratesPerKw.set(feerates)
        Future.successful(())
      case _ =>
        throw new RuntimeException("no channel")
    }
    logger.info(s"current feeratesPerKB=${feeratesPerKB.get()} feeratesPerKw=${feeratesPerKw.get()}")

    val watcher = system.deadLetters
    val router = system.deadLetters
    val wallet = eclairWallet

    // do not change the name of this actor. it is used in the configuration to specify a custom bounded mailbox

    val backupHandler = system.actorOf(SimpleSupervisor.props(
      BackupHandler.props(
        nodeParams.db,
        new File(chaindir, "eclair.sqlite.bak"),
        if (config.hasPath("backup-notify-script")) Some(config.getString("backup-notify-script")) else None
      ), "backuphandler", SupervisorStrategy.Resume))
    val audit = system.actorOf(SimpleSupervisor.props(Auditor.props(nodeParams), "auditor", SupervisorStrategy.Resume))
    val register = system.actorOf(SimpleSupervisor.props(Props(new Register), "register", SupervisorStrategy.Resume))
    val commandBuffer = system.actorOf(SimpleSupervisor.props(Props(new CommandBuffer(nodeParams, register)), "command-buffer", SupervisorStrategy.Resume))
    val paymentHandler = system.actorOf(SimpleSupervisor.props(PaymentHandler.props(nodeParams, commandBuffer), "payment-handler", SupervisorStrategy.Resume))
    val relayer = system.actorOf(SimpleSupervisor.props(Relayer.props(nodeParams, router, register, commandBuffer, paymentHandler, None), "relayer", SupervisorStrategy.Resume))

    val switchboard = system.actorOf(SimpleSupervisor.props(Switchboard.props(nodeParams, router, watcher, relayer, paymentHandler, wallet), "switchboard", SupervisorStrategy.Resume))

    pReceiveDone.future
  }

}

/**
 * This actor monitors waits for a payment to arrive and terminates the actor system when that happens, or if
 * no progress has been made for a long time.
 */
class ReceiveListener(pReceiveDone: Promise[Boolean]) extends Actor with ActorLogging {

  import scala.concurrent.duration._

  context.setReceiveTimeout(60 second)
  context.system.eventStream.subscribe(self, classOf[PaymentReceived])

  override def receive: Receive = {
    case p: PaymentReceived =>
      log.info(s"received a payment (paymentHash=${p.paymentHash}), waiting a bit more...")
    case ReceiveTimeout ⇒
      log.info("no payment received for a while, system will terminate...")
      pReceiveDone.success(false)
  }
}
