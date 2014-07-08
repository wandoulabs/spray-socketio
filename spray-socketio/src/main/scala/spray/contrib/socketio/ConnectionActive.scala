package spray.contrib.socketio

import akka.actor.{ PoisonPill, Actor, ActorRef, Terminated, ActorSystem, Props, ActorLogging, ActorSelection }
import akka.contrib.pattern.ClusterClient
import akka.contrib.pattern.ClusterReceptionistExtension
import akka.contrib.pattern.ClusterSharding
import akka.contrib.pattern.ShardRegion
import akka.contrib.pattern.DistributedPubSubMediator.{ Publish, Subscribe, SubscribeAck, Unsubscribe }
import akka.event.LoggingAdapter
import akka.pattern.ask
import akka.routing.ConsistentHashingRouter.ConsistentHashable
import akka.util.ByteString
import org.parboiled.errors.ParsingException
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import spray.contrib.socketio
import spray.contrib.socketio.packet.AckPacket
import spray.contrib.socketio.packet.ConnectPacket
import spray.contrib.socketio.packet.DataPacket
import spray.contrib.socketio.packet.DisconnectPacket
import spray.contrib.socketio.packet.EventPacket
import spray.contrib.socketio.packet.HeartbeatPacket
import spray.contrib.socketio.packet.JsonPacket
import spray.contrib.socketio.packet.MessagePacket
import spray.contrib.socketio.packet.Packet
import spray.contrib.socketio.packet.PacketParser
import spray.contrib.socketio.transport.Transport
import spray.http.HttpOrigin
import spray.http.Uri

object ConnectionActive {

  case object AskConnectedTime

  sealed trait Event extends Serializable

  sealed trait Command extends Serializable {
    def sessionId: String
  }

  final case class CreateSession(sessionId: String) extends Command

  final case class Connecting(sessionId: String, query: Uri.Query, origins: Seq[HttpOrigin], transportConnection: ActorRef, transport: Transport) extends Command with Event
  final case class Closing(sessionId: String, transportConnection: ActorRef) extends Command with Event
  final case class SubscribeBroadcast(sessionId: String, endpoint: String, room: String) extends Command with Event
  final case class UnsubscribeBroadcast(sessionId: String, endpoint: String, room: String) extends Command with Event

  // called by connection
  final case class OnGet(sessionId: String, transportConnection: ActorRef) extends Command
  final case class OnPost(sessionId: String, transportConnection: ActorRef, payload: ByteString) extends Command
  final case class OnFrame(sessionId: String, payload: ByteString) extends Command

  // called by business logic
  final case class SendMessage(sessionId: String, endpoint: String, msg: String) extends Command
  final case class SendJson(sessionId: String, endpoint: String, json: String) extends Command
  final case class SendEvent(sessionId: String, endpoint: String, name: String, args: Either[String, Seq[String]]) extends Command
  final case class SendPackets(sessionId: String, packets: Seq[Packet]) extends Command

  final case class SendAck(sessionId: String, originalPacket: DataPacket, args: String) extends Command

  /**
   * ask me to publish an OnBroadcast data
   */
  final case class Broadcast(sessionId: String, room: String, packet: Packet) extends Command

  final case class GetStatus(sessionId: String) extends Command

  final case class Status(sessionId: String, connectionTime: Long, location: String) extends Serializable

  /**
   * Broadcast event to be published or recevived
   */
  final case class OnBroadcast(sessionId: String, room: String, packet: Packet)

  /**
   * Packet event to be published
   */
  final case class OnPacket[T <: Packet](packet: T, connContext: ConnectionContext) extends ConsistentHashable {
    override def consistentHashKey: Any = connContext.sessionId
  }

  val shardName: String = "ConnectionActives"

  val idExtractor: ShardRegion.IdExtractor = {
    case cmd: Command => (cmd.sessionId, cmd)
  }

  val shardResolver: ShardRegion.ShardResolver = {
    case cmd: Command => (math.abs(cmd.sessionId.hashCode) % 100).toString
  }

  /**
   * It is recommended to load the ClusterReceptionistExtension when the actor
   * system is started by defining it in the akka.extensions configuration property:
   *   akka.extensions = ["akka.contrib.pattern.ClusterReceptionistExtension"]
   */
  def startShard(system: ActorSystem, connectionActiveProps: Props) {
    ClusterSharding(system).start(
      typeName = ConnectionActive.shardName,
      entryProps = Some(connectionActiveProps),
      idExtractor = ConnectionActive.idExtractor,
      shardResolver = ConnectionActive.shardResolver)
    ClusterReceptionistExtension(system).registerService(
      ClusterSharding(system).shardRegion(ConnectionActive.shardName))
  }

  final class SystemSingletons(system: ActorSystem) {
    lazy val clusterClient: ActorRef = {
      import scala.collection.JavaConversions._
      val initialContacts = system.settings.config.getStringList("spray.socketio.cluster.client-initial-contacts").toSet
      system.actorOf(ClusterClient.props(initialContacts map system.actorSelection), "socketio-cluster-connactive-client")
    }
  }

  private var singletons: SystemSingletons = _
  private val singletonsMutex = new AnyRef
  /**
   * Get the SystemSingletons, create it if none existed.
   *
   * @Note only one will be created no matter how many ActorSystems, actually
   * one ActorSystem per application usaully.
   */
  def apply(system: ActorSystem): SystemSingletons = {
    if (singletons eq null) {
      singletonsMutex synchronized {
        if (singletons eq null) {
          singletons = new SystemSingletons(system)
        }
      }
    }
    singletons
  }

  final case class State(connectionContext: Option[ConnectionContext], transportConnection: ActorRef, topics: immutable.Set[String], disconnected: Boolean)

  val GlobalConnectPacket = ConnectPacket()
  val GlobalDisconnectPacket = DisconnectPacket()
}

/**
 *
 * transportConnection <1..n--1> connectionActive <1--1> connContext <1--n> transport
 */
trait ConnectionActive { _: Actor =>
  import ConnectionActive._
  import context.dispatcher

  def log: LoggingAdapter

  def namespaceMediator: ActorRef

  def broadcastMediator: ActorRef

  var pendingPackets = immutable.Queue[Packet]()

  var state: State = State(None, null, immutable.Set[String](), false)

  var isReplaying = false

  val startTime = System.currentTimeMillis

  def updateState(evt: Any, newState: State) {
    state = newState
  }

  def close() {
    self ! PoisonPill
  }

  def working: Receive = {
    case CreateSession(_) => // may be forwarded by resolver, just ignore it.

    case cmd @ Connecting(sessionId, query, origins, transportConn, transport) =>
      if (!isReplaying) {
        log.info("Connecting: {}, {}", sessionId, state.connectionContext)
      }

      state.connectionContext match {
        case Some(existed) =>
          if (!isReplaying) {
            state = state.copy(transportConnection = transportConn)
            existed.bindTransport(transport)
            onPacket(cmd)(GlobalConnectPacket)
          }
        case None =>
          state = state.copy(
            connectionContext = Some(new ConnectionContext(sessionId, query, origins)),
            transportConnection = transportConn)
          state.connectionContext.foreach(_.bindTransport(transport))
          onPacket(cmd)(GlobalConnectPacket)
      }

    case cmd @ Closing(sessionId, transportConn) =>
      log.info("Closing: {}, {}", sessionId, state.connectionContext)
      if (state.transportConnection == transportConn) {
        if (!state.disconnected) { // make sure only send disconnect packet one time
          onPacket(cmd)(GlobalDisconnectPacket)
        }
        close
      }

    case Terminated(ref) =>
      log.info("Terminated: {}, {}", state.connectionContext, ref)
      if (state.transportConnection == ref) {
        if (!state.disconnected) {
          onPacket(null)(GlobalDisconnectPacket)
        }
        close
      }

    case cmd @ OnFrame(sessionId, payload) =>
      onPayload(cmd)(payload)
    case cmd @ OnPost(sessionId, transportConnection, payload) =>
      // response an empty entity to release POST before message processing
      state.connectionContext foreach { ctx =>
        ctx.transport.write(ctx, transportConnection, "")
      }
      onPayload(cmd)(payload)
    case OnGet(sessionId, transportConnection) =>
      state.connectionContext foreach { ctx =>
        pendingPackets = ctx.transport.writeSingle(ctx, transportConnection, isSendingNoopWhenEmpty = true, pendingPackets)
      }

    case SendMessage(sessionId, endpoint, msg)      => sendMessage(endpoint, msg)
    case SendJson(sessionId, endpoint, json)        => sendJson(endpoint, json)
    case SendEvent(sessionId, endpoint, name, args) => sendEvent(endpoint, name, args)
    case SendPackets(sessionId, packets)            => sendPacket(packets: _*)

    case SendAck(sessionId, packet, args)           => sendAck(packet, args)

    case Broadcast(sessionId, room, packet)         => publishToBroadcast(OnBroadcast(sessionId, room, packet))
    case OnBroadcast(senderSessionId, room, packet) => sendPacket(packet) // write to client

    case cmd @ SubscribeBroadcast(sessionId, endpoint, room) =>
      val topic = socketio.topicForBroadcast(endpoint, room)
      updateState(cmd, state.copy(topics = state.topics + topic))
      subscribeBroadcast(topic)

    case cmd @ UnsubscribeBroadcast(sessionId, endpoint, room) =>
      val topic = socketio.topicForBroadcast(endpoint, room)
      updateState(cmd, state.copy(topics = state.topics - topic))
      unsubscribeBroadcast(topic)

    case AskConnectedTime =>
      sender() ! System.currentTimeMillis - startTime

    case GetStatus(sessionId) =>
      val sessionId = state.connectionContext.map(_.sessionId).getOrElse(null)
      val location = if (state.transportConnection != null && state.transportConnection.path != null) state.transportConnection.path.toSerializationFormat else null
      sender() ! Status(sessionId, System.currentTimeMillis - startTime, location)
  }

  // --- reacts

  private def onPayload(cmd: Command)(payload: ByteString) {
    PacketParser(payload) match {
      case Success(packets)              => packets foreach onPacket(cmd)
      case Failure(ex: ParsingException) => log.warning("Invalid socket.io packet: {} ...", payload.take(50).utf8String)
      case Failure(ex)                   => log.warning("Exception during parse socket.io packet: {} ..., due to: {}", payload.take(50).utf8String, ex)
    }
  }

  private def onPacket(cmd: Command)(packet: Packet) {
    packet match {
      case HeartbeatPacket =>

      case ConnectPacket(endpoint, args) =>
        if (!isReplaying) {
          state.connectionContext foreach { ctx => publishToNamespace(OnPacket(packet, ctx)) }
        }

        if (state.connectionContext.exists(_.transport == transport.WebSocket)) {
          context watch state.transportConnection
        }
        val topic = socketio.topicForBroadcast(endpoint, "")
        updateState(cmd, state.copy(topics = state.topics + topic, disconnected = false))
        subscribeBroadcast(topic).onComplete {
          case Success(ack) =>
            // bounce connect packet back to client
            if (!isReplaying) {
              sendPacket(packet)
            }
          case Failure(ex) =>
            log.warning("Failed to subscribe to medietor on topic {}: {}", topic, ex.getMessage)
        }

      case DisconnectPacket(endpoint) =>
        if (endpoint == "") {
          if (!isReplaying) {
            state.connectionContext foreach { ctx => publishDisconnect(ctx) }
          }
          if (state.transportConnection != null) {
            state.transportConnection ! Disconnect
            context unwatch state.transportConnection
          }
          updateState(cmd, state.copy(topics = Set(), disconnected = true))
          state.topics foreach unsubscribeBroadcast
          // do not stop self, waiting for Closing message
        } else {
          if (!isReplaying) {
            state.connectionContext foreach { ctx => publishToNamespace(OnPacket(packet, ctx)) }
          }
          val topic = socketio.topicForBroadcast(endpoint, "")
          updateState(cmd, state.copy(topics = state.topics - topic))
          unsubscribeBroadcast(topic)
        }

      case _ =>
        // for data packet that requests ack and has no ack data, automatically ack
        packet match {
          case x: DataPacket if x.isAckRequested && !x.hasAckData => sendAck(x, "[]")
          case _ =>
        }
        state.connectionContext foreach { ctx => publishToNamespace(OnPacket(packet, ctx)) }
    }
  }

  def sendMessage(endpoint: String, msg: String) {
    val packet = MessagePacket(-1L, false, endpoint, msg)
    sendPacket(packet)
  }

  def sendJson(endpoint: String, json: String) {
    val packet = JsonPacket(-1L, false, endpoint, json)
    sendPacket(packet)
  }

  def sendEvent(endpoint: String, name: String, args: Either[String, Seq[String]]) {
    val packet = args match {
      case Left(x)   => EventPacket(-1L, false, endpoint, name, x)
      case Right(xs) => EventPacket(-1L, false, endpoint, name, xs)
    }
    sendPacket(packet)
  }

  /**
   * enqueue packets, and let tranport decide whether to flush them right now or pend flush
   */
  def sendPacket(packets: Packet*) {
    var updatePendingPackets = pendingPackets
    packets foreach { packet => updatePendingPackets = updatePendingPackets.enqueue(packet) }
    log.debug("Enqueued {}, pendingPackets: {}", packets, pendingPackets)
    state.connectionContext foreach { ctx =>
      updatePendingPackets = ctx.transport.flushOrWait(ctx, state.transportConnection, updatePendingPackets)
    }
    pendingPackets = updatePendingPackets
  }

  def sendAck(originalPacket: DataPacket, args: String) {
    sendPacket(AckPacket(originalPacket.id, args))
  }

  def publishDisconnect(ctx: ConnectionContext) {
    namespaceMediator ! Publish(socketio.topicForDisconnect, OnPacket(GlobalDisconnectPacket, ctx))
  }

  def publishToNamespace[T <: Packet](msg: OnPacket[T]) {
    namespaceMediator ! Publish(socketio.topicForNamespace(msg.packet.endpoint), msg)
  }

  def publishToBroadcast(msg: OnBroadcast) {
    broadcastMediator ! Publish(socketio.topicForBroadcast(msg.packet.endpoint, msg.room), msg)
  }

  def subscribeBroadcast(topic: String): Future[SubscribeAck] = {
    broadcastMediator.ask(Subscribe(topic, self))(socketio.actorResolveTimeout).mapTo[SubscribeAck]
  }

  def unsubscribeBroadcast(topic: String) {
    broadcastMediator ! Unsubscribe(topic, self)
  }

}

object ConnectionActiveClusterClient {
  def props(path: String, clusterClient: ActorRef) = Props(classOf[ConnectionActiveClusterClient], path, clusterClient)

  def getClient(system: ActorSystem, initialContacts: Set[ActorSelection]) = {
  }

  private var _client: ActorRef = _
  /**
   * Proxied cluster client
   */
  def apply(system: ActorSystem) = {
    if (_client eq null) {
      val originalClient = ConnectionActive(system).clusterClient
      val shardingName = system.settings.config.getString("akka.contrib.cluster.sharding.guardian-name")
      _client = system.actorOf(props(s"/user/${shardingName}/${ConnectionActive.shardName}", originalClient))
    }
    _client
  }
}

/**
 * A proxy actor that runs on the namespace nodes to make forwarding msg to ConnectionActive easy.
 *
 * @param path ConnectionActive sharding service's path
 * @param client [[ClusterClient]] to access SocketIO Cluster
 */
class ConnectionActiveClusterClient(path: String, clusterClient: ActorRef) extends Actor with ActorLogging {
  def receive: Actor.Receive = {
    case cmd: ConnectionActive.Command => clusterClient forward ClusterClient.Send(path, cmd, false)
  }
}
