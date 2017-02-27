package fr.acinq.eclair.gui

import java.util.function.Predicate
import javafx.application.Platform
import javafx.event.{ActionEvent, EventHandler}
import javafx.fxml.FXMLLoader
import javafx.scene.layout.VBox
import javafx.stage.Stage

import akka.actor.{Actor, ActorLogging, ActorRef}
import fr.acinq.bitcoin.Crypto.PublicKey
import fr.acinq.bitcoin._
import fr.acinq.eclair.channel._
import fr.acinq.eclair.gui.controllers.{ChannelPaneController, MainController, PeerChannel, PeerNode}
import fr.acinq.eclair.io.Reconnect
import fr.acinq.eclair.router.{ChannelDiscovered, ChannelLost, NodeDiscovered, NodeLost}
import fr.acinq.eclair.transactions.CommitmentSpec
import fr.acinq.eclair.{Globals, Setup}
import org.jgrapht.graph.{DefaultEdge, SimpleGraph}


/**
  * Created by PM on 16/08/2016.
  */
class GUIUpdater(primaryStage: Stage, mainController: MainController, setup: Setup) extends Actor with ActorLogging {

  class NamedEdge(val id: BinaryData) extends DefaultEdge {
    override def toString: String = s"${id.toString.take(8)}..."
  }

  val graph = new SimpleGraph[BinaryData, NamedEdge](classOf[NamedEdge])
  graph.addVertex(Globals.Node.publicKey)

  def receive: Receive = main(Map())

  def createChannelPanel(channel: ActorRef, peer: ActorRef, remoteNodeId: PublicKey, isFunder: Boolean, temporaryChannelId: Long): (ChannelPaneController, VBox) = {
    log.info(s"new channel: $channel")
    val loader = new FXMLLoader(getClass.getResource("/gui/main/channelPane.fxml"))
    val channelPaneController = new ChannelPaneController(s"$remoteNodeId")
    loader.setController(channelPaneController)
    val root = loader.load[VBox]
    channelPaneController.nodeId.setText(s"$remoteNodeId")
    channelPaneController.channelId.setText(java.lang.Long.toHexString(temporaryChannelId))
    channelPaneController.funder.setText(if (isFunder) "Yes" else "No")
    channelPaneController.reconnect.setDisable(!isFunder)
    channelPaneController.close.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = channel ! CMD_CLOSE(None)
    })
    channelPaneController.reconnect.setOnAction(new EventHandler[ActionEvent] {
      override def handle(event: ActionEvent) = peer ! Reconnect
    })
    (channelPaneController, root)
  }

  def updateBalance(channelPaneController: ChannelPaneController, commitments: Commitments) = {
    val spec = commitments.localCommit.spec
    channelPaneController.capacity.setText(s"${millisatoshi2millibtc(MilliSatoshi(spec.totalFunds)).amount}")
    channelPaneController.amountUs.setText(s"${millisatoshi2millibtc(MilliSatoshi(spec.toLocalMsat)).amount}")
    channelPaneController.balanceBar.setProgress(spec.toLocalMsat.toDouble / spec.totalFunds)
  }

  def main(m: Map[ActorRef, ChannelPaneController]): Receive = {

    case ChannelCreated(channel, peer, remoteNodeId, isFunder, temporaryChannelId) =>
      val (channelPaneController, root) = createChannelPanel(channel, peer, remoteNodeId, isFunder, temporaryChannelId)
      Platform.runLater(new Runnable() {
        override def run = mainController.channelBox.getChildren.addAll(root)
      })
      context.become(main(m + (channel -> channelPaneController)))

    case ChannelRestored(channel, peer, remoteNodeId, isFunder, channelId, currentData) =>
      val (channelPaneController, root) = createChannelPanel(channel, peer, remoteNodeId, isFunder, channelId)
      currentData match {
        case d: HasCommitments => updateBalance(channelPaneController, d.commitments)
        case _ => {}
      }
      Platform.runLater(new Runnable() {
        override def run = mainController.channelBox.getChildren.addAll(root)
      })
      context.become(main(m + (channel -> channelPaneController)))

    case ChannelIdAssigned(channel, channelId) if m.contains(channel) =>
      val channelPaneController = m(channel)
      Platform.runLater(new Runnable() {
        override def run = {
          channelPaneController.channelId.setText(java.lang.Long.toHexString(channelId))
        }
      })

    case ChannelStateChanged(channel, _, _, _, currentState, _) if m.contains(channel) =>
      val channelPaneController = m(channel)
      Platform.runLater(new Runnable() {
        override def run = {
          channelPaneController.state.setText(currentState.toString)
        }
      })

    case ChannelSignatureReceived(channel, commitments) if m.contains(channel) =>
      val channelPaneController = m(channel)
      Platform.runLater(new Runnable() {
        override def run = updateBalance(channelPaneController, commitments)
      })

    case NodeDiscovered(nodeAnnouncement) =>
      log.debug(s"peer node discovered with node id = ${nodeAnnouncement.nodeId}")
      mainController.allNodesList.add(new PeerNode(nodeAnnouncement))
      Platform.runLater(new Runnable() {
        override def run = mainController.allNodesTab.setText(s"Nodes (${mainController.allNodesList.size})")
      })

    case NodeLost(nodeId) =>
      log.debug(s"peer node lost with node id = ${nodeId}")
      mainController.allNodesList.removeIf(new Predicate[PeerNode] {
        override def test(pn: PeerNode) = nodeId.equals(pn.id)
      })
      Platform.runLater(new Runnable() {
        override def run = mainController.allNodesTab.setText(s"Nodes (${mainController.allNodesList.size})")
      })

    case ChannelDiscovered(channelAnnouncement, _) =>
      log.debug(s"peer channel discovered with channel id = ${channelAnnouncement.channelId}")
      mainController.allChannelsList.add(new PeerChannel(channelAnnouncement))
      Platform.runLater(new Runnable() {
        override def run = mainController.allChannelsTab.setText(s"Channels (${mainController.allChannelsList.size})")
      })

    case ChannelLost(channelId) =>
      log.debug(s"peer channel lost with channel id = ${channelId}")
      mainController.allChannelsList.removeIf(new Predicate[PeerChannel] {
        override def test(pc: PeerChannel) = pc.id.get == channelId
      })
      Platform.runLater(new Runnable() {
        override def run = mainController.allChannelsTab.setText(s"Channels (${mainController.allChannelsList.size})")
      })
  }
}
