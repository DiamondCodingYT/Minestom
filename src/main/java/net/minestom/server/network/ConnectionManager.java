package net.minestom.server.network;

import io.netty.channel.Channel;
import net.minestom.server.MinecraftServer;
import net.minestom.server.chat.ChatColor;
import net.minestom.server.chat.ColoredText;
import net.minestom.server.chat.JsonMessage;
import net.minestom.server.entity.Player;
import net.minestom.server.entity.fakeplayer.FakePlayer;
import net.minestom.server.event.player.AsyncPlayerPreLoginEvent;
import net.minestom.server.event.player.PlayerLoginEvent;
import net.minestom.server.instance.Instance;
import net.minestom.server.listener.manager.ClientPacketConsumer;
import net.minestom.server.listener.manager.ServerPacketConsumer;
import net.minestom.server.lock.Acquirable;
import net.minestom.server.network.packet.client.login.LoginStartPacket;
import net.minestom.server.network.packet.server.login.LoginSuccessPacket;
import net.minestom.server.network.packet.server.play.ChatMessagePacket;
import net.minestom.server.network.packet.server.play.DisconnectPacket;
import net.minestom.server.network.packet.server.play.KeepAlivePacket;
import net.minestom.server.network.player.NettyPlayerConnection;
import net.minestom.server.network.player.PlayerConnection;
import net.minestom.server.utils.PacketUtils;
import net.minestom.server.utils.async.AsyncUtils;
import net.minestom.server.utils.callback.validator.PlayerValidator;
import net.minestom.server.utils.collection.AcquirableCollectionView;
import net.minestom.server.utils.validate.Check;
import org.apache.commons.text.similarity.JaroWinklerDistance;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Manages the connected clients.
 */
public final class ConnectionManager {

    private static final long KEEP_ALIVE_DELAY = 10_000;
    private static final long KEEP_ALIVE_KICK = 30_000;
    private static final ColoredText TIMEOUT_TEXT = ColoredText.of(ChatColor.RED + "Timeout");

    private final Queue<Player> waitingPlayers = new ConcurrentLinkedQueue<>();

    private final Set<Acquirable<Player>> acquirablePlayers = new CopyOnWriteArraySet<>();
    private final Set<Acquirable<Player>> unmodifiableAcquirablePlayers = Collections.unmodifiableSet(acquirablePlayers);
    private final Set<Player> players = new CopyOnWriteArraySet<>();
    private final Set<Player> unmodifiablePlayers = Collections.unmodifiableSet(players);
    private final Map<PlayerConnection, Player> connectionPlayerMap = new ConcurrentHashMap<>();

    // All the consumers to call once a packet is received
    private final List<ClientPacketConsumer> receiveClientPacketConsumers = new CopyOnWriteArrayList<>();
    // All the consumers to call once a packet is sent
    private final List<ServerPacketConsumer> sendClientPacketConsumers = new CopyOnWriteArrayList<>();
    // The uuid provider once a player login
    private UuidProvider uuidProvider;
    // The player provider to have your own Player implementation
    private PlayerProvider playerProvider;
    // The consumers to call once a player connect, mostly used to init events
    private final List<Consumer<Player>> playerInitializations = new CopyOnWriteArrayList<>();

    private JsonMessage shutdownText = ColoredText.of(ChatColor.RED, "The server is shutting down.");

    /**
     * Gets the {@link Player} linked to a {@link PlayerConnection}.
     *
     * @param connection the player connection
     * @return the player linked to the connection, null if not found
     */
    @Nullable
    public Acquirable<Player> getPlayer(@NotNull PlayerConnection connection) {
        Player player = connectionPlayerMap.get(connection);
        return player != null ? player.getAcquiredElement() : null;
    }

    /**
     * Gets all online players.
     * <p>
     * {@link Acquirable} collections can be computed using the {@link net.minestom.server.lock.Acquisition}
     * utils methods.
     *
     * @return an unmodifiable collection containing all the online players
     */
    @NotNull
    public Collection<Acquirable<Player>> getOnlinePlayers() {
        return unmodifiableAcquirablePlayers;
    }

    @NotNull
    public Collection<Player> getUnwrapOnlinePlayers() {
        return new AcquirableCollectionView<>(getOnlinePlayers());
    }

    /**
     * Finds the closest player matching a given username.
     * <p>
     *
     * @param username the player username (can be partial)
     * @return the closest match, null if no players are online
     */
    @Nullable
    public Acquirable<Player> findPlayer(@NotNull String username) {
        Acquirable<Player> exact = getPlayer(username);
        if (exact != null) return exact;

        String lowercase = username.toLowerCase();
        double currentDistance = 0;
        // Unsafe loop, ok because we are only comparing usernames
        for (Player player : getUnwrapOnlinePlayers()) {
            final String unwrapUsername = player.getUsername().toLowerCase();
            final double distance = new JaroWinklerDistance().apply(lowercase, unwrapUsername);
            if (distance > currentDistance) {
                currentDistance = distance;
                exact = player.getAcquiredElement();
            }
        }
        return exact;
    }

    /**
     * Gets the first player which validate {@link String#equalsIgnoreCase(String)}.
     * <p>
     * This can cause issue if two or more players have the same username.
     *
     * @param username the player username (ignoreCase)
     * @return the first player who validate the username condition, null if none was found
     */
    @Nullable
    public Acquirable<Player> getPlayer(@NotNull String username) {
        // Unsafe loop, ok because we are only comparing usernames
        for (Player player : getUnwrapOnlinePlayers()) {
            if (player.getUsername().equalsIgnoreCase(username))
                return player.getAcquiredElement();
        }
        return null;
    }

    /**
     * Gets the first player which validate {@link UUID#equals(Object)}.
     * <p>
     * This can cause issue if two or more players have the same UUID.
     *
     * @param uuid the player UUID
     * @return the first player who validate the UUID condition, null if none was found
     */
    @Nullable
    public Acquirable<Player> getPlayer(@NotNull UUID uuid) {
        // Unsafe loop, ok because we are only comparing UUIDs
        for (Player player : getUnwrapOnlinePlayers()) {
            if (player.getUuid().equals(uuid))
                return player.getAcquiredElement();
        }
        return null;
    }

    /**
     * Sends a {@link JsonMessage} to all online players who validate the condition {@code condition}.
     *
     * @param jsonMessage the message to send, probably a {@link net.minestom.server.chat.ColoredText} or {@link net.minestom.server.chat.RichMessage}
     * @param condition   the condition to receive the message
     */
    public void broadcastMessage(@NotNull JsonMessage jsonMessage, @Nullable PlayerValidator condition) {
        final Collection<Acquirable<Player>> recipients = getRecipients(condition);

        if (!recipients.isEmpty()) {
            final String jsonText = jsonMessage.toString();
            broadcastJson(jsonText, recipients);
        }
    }

    /**
     * Sends a {@link JsonMessage} to all online players.
     *
     * @param jsonMessage the message to send, probably a {@link net.minestom.server.chat.ColoredText} or {@link net.minestom.server.chat.RichMessage}
     */
    public void broadcastMessage(@NotNull JsonMessage jsonMessage) {
        broadcastMessage(jsonMessage, null);
    }

    private void broadcastJson(@NotNull String json, @NotNull Collection<Acquirable<Player>> recipients) {
        ChatMessagePacket chatMessagePacket =
                new ChatMessagePacket(json, ChatMessagePacket.Position.SYSTEM_MESSAGE);

        PacketUtils.sendGroupedPacket(recipients, chatMessagePacket);
    }

    private Collection<Acquirable<Player>> getRecipients(@Nullable PlayerValidator condition) {
        Collection<Acquirable<Player>> recipients;

        // Get the recipients
        if (condition == null) {
            recipients = getOnlinePlayers();
        } else {
            recipients = new ArrayList<>();
            getOnlinePlayers().forEach(acquirablePlayer -> {
                final Player player = acquirablePlayer.unwrap();
                final boolean result = condition.isValid(player);
                if (result)
                    recipients.add(acquirablePlayer);
            });
        }

        return recipients;
    }

    /**
     * Gets all the listeners which are called for each packet received.
     *
     * @return a list of packet's consumers
     */
    @NotNull
    public List<ClientPacketConsumer> getReceivePacketConsumers() {
        return receiveClientPacketConsumers;
    }

    /**
     * Adds a consumer to call once a packet is received.
     *
     * @param clientPacketConsumer the packet consumer
     */
    public void onPacketReceive(@NotNull ClientPacketConsumer clientPacketConsumer) {
        this.receiveClientPacketConsumers.add(clientPacketConsumer);
    }

    /**
     * Gets all the listeners which are called for each packet sent.
     *
     * @return a list of packet's consumers
     */
    @NotNull
    public List<ServerPacketConsumer> getSendPacketConsumers() {
        return sendClientPacketConsumers;
    }

    /**
     * Adds a consumer to call once a packet is sent.
     *
     * @param serverPacketConsumer the packet consumer
     */
    public void onPacketSend(@NotNull ServerPacketConsumer serverPacketConsumer) {
        this.sendClientPacketConsumers.add(serverPacketConsumer);
    }

    /**
     * Changes how {@link UUID} are attributed to players.
     * <p>
     * Shouldn't be override if already defined.
     * <p>
     * Be aware that it is possible for an UUID provider to be ignored, for example in the case of a proxy (eg: velocity).
     *
     * @param uuidProvider the new player connection uuid provider,
     *                     setting it to null would apply a random UUID for each player connection
     * @see #getPlayerConnectionUuid(PlayerConnection, String)
     */
    public void setUuidProvider(@Nullable UuidProvider uuidProvider) {
        this.uuidProvider = uuidProvider;
    }

    /**
     * Computes the UUID of the specified connection.
     * Used in {@link net.minestom.server.network.packet.client.login.LoginStartPacket} in order
     * to give the player the right {@link UUID}.
     *
     * @param playerConnection the player connection
     * @param username         the username given by the connection
     * @return the uuid based on {@code playerConnection}
     * return a random UUID if no UUID provider is defined see {@link #setUuidProvider(UuidProvider)}
     */
    @NotNull
    public UUID getPlayerConnectionUuid(@NotNull PlayerConnection playerConnection, @NotNull String username) {
        if (uuidProvider == null)
            return UUID.randomUUID();
        return uuidProvider.provide(playerConnection, username);
    }

    /**
     * Changes the {@link Player} provider, to change which object to link to him.
     *
     * @param playerProvider the new {@link PlayerProvider}, can be set to null to apply the default provider
     */
    public void setPlayerProvider(@Nullable PlayerProvider playerProvider) {
        this.playerProvider = playerProvider;
    }

    /**
     * Retrieves the current {@link PlayerProvider}, can be the default one if none is defined.
     *
     * @return the current {@link PlayerProvider}
     */
    @NotNull
    public PlayerProvider getPlayerProvider() {
        return playerProvider == null ? playerProvider = Player::new : playerProvider;
    }

    /**
     * Those are all the consumers called when a new {@link Player} join.
     *
     * @return an unmodifiable list containing all the {@link Player} initialization consumer
     */
    @NotNull
    public List<Consumer<Player>> getPlayerInitializations() {
        return Collections.unmodifiableList(playerInitializations);
    }

    /**
     * Adds a new player initialization consumer. Those are called when a {@link Player} join,
     * mainly to add event callbacks to the player.
     * <p>
     * This callback should be exclusively used to add event listeners since it is called directly after a
     * player join (before any chunk is sent) and the client behavior can therefore be unpredictable.
     * You can add your "init" code in {@link net.minestom.server.event.player.PlayerLoginEvent}
     * or even {@link AsyncPlayerPreLoginEvent}.
     *
     * @param playerInitialization the {@link Player} initialization consumer
     */
    public void addPlayerInitialization(@NotNull Consumer<Player> playerInitialization) {
        this.playerInitializations.add(playerInitialization);
    }

    /**
     * Gets the kick reason when the server is shutdown using {@link MinecraftServer#stopCleanly()}.
     *
     * @return the kick reason in case on a shutdown
     */
    @NotNull
    public JsonMessage getShutdownText() {
        return shutdownText;
    }

    /**
     * Changes the kick reason in case of a shutdown.
     *
     * @param shutdownText the new shutdown kick reason
     * @see #getShutdownText()
     */
    public void setShutdownText(@NotNull JsonMessage shutdownText) {
        this.shutdownText = shutdownText;
    }

    /**
     * Adds a new {@link Player} in the players list.
     * Is currently used at
     * {@link LoginStartPacket#process(PlayerConnection)}
     * and in {@link FakePlayer#initPlayer(UUID, String, Consumer)}.
     *
     * @param player the player to add
     */
    public synchronized void registerPlayer(@NotNull Player player) {
        this.acquirablePlayers.add(player.getAcquiredElement());
        this.players.add(player);
        this.connectionPlayerMap.put(player.getPlayerConnection(), player);
    }

    /**
     * Removes a {@link Player} from the players list.
     * <p>
     * Used during disconnection, you shouldn't have to do it manually.
     *
     * @param connection the player connection
     * @see PlayerConnection#disconnect() to properly disconnect a player
     */
    public void removePlayer(@NotNull PlayerConnection connection) {
        final Player player = this.connectionPlayerMap.get(connection);
        if (player == null)
            return;

        this.acquirablePlayers.remove(player.getAcquiredElement());
        this.players.remove(player);
        this.connectionPlayerMap.remove(connection);
    }

    /**
     * Calls the player initialization callbacks and the event {@link AsyncPlayerPreLoginEvent}.
     * <p>
     * Sends a {@link LoginSuccessPacket} if successful (not kicked)
     * and change the connection state to {@link ConnectionState#PLAY}.
     *
     * @param player   the player
     * @param register true to register the newly created player in {@link ConnectionManager} lists
     */
    public void startPlayState(@NotNull Player player, boolean register) {
        // Init player (register events)
        for (Consumer<Player> playerInitialization : getPlayerInitializations()) {
            playerInitialization.accept(player);
        }

        AsyncUtils.runAsync(() -> {
            String username = player.getUsername();
            UUID uuid = player.getUuid();

            // Call pre login event
            AsyncPlayerPreLoginEvent asyncPlayerPreLoginEvent = new AsyncPlayerPreLoginEvent(player, username, uuid);
            player.callEvent(AsyncPlayerPreLoginEvent.class, asyncPlayerPreLoginEvent);

            // Close the player channel if he has been disconnected (kick)
            final boolean online = player.isOnline();
            if (!online) {
                final PlayerConnection playerConnection = player.getPlayerConnection();

                if (playerConnection instanceof NettyPlayerConnection) {
                    ((NettyPlayerConnection) playerConnection).getChannel().flush();
                }

                //playerConnection.disconnect();
                return;
            }

            // Change UUID/Username based on the event
            {
                final String eventUsername = asyncPlayerPreLoginEvent.getUsername();
                final UUID eventUuid = asyncPlayerPreLoginEvent.getPlayerUuid();

                if (!player.getUsername().equals(eventUsername)) {
                    player.setUsernameField(eventUsername);
                    username = eventUsername;
                }

                if (!player.getUuid().equals(eventUuid)) {
                    player.setUuid(eventUuid);
                    uuid = eventUuid;
                }
            }

            // Send login success packet
            {
                final PlayerConnection connection = player.getPlayerConnection();

                LoginSuccessPacket loginSuccessPacket = new LoginSuccessPacket(uuid, username);
                if (connection instanceof NettyPlayerConnection) {
                    ((NettyPlayerConnection) connection).writeAndFlush(loginSuccessPacket);
                } else {
                    connection.sendPacket(loginSuccessPacket);
                }

                connection.setConnectionState(ConnectionState.PLAY);
            }

            // Add the player to the waiting list
            addWaitingPlayer(player);

            if (register) {
                registerPlayer(player);
            }
        });
    }

    /**
     * Creates a {@link Player} using the defined {@link PlayerProvider}
     * and execute {@link #startPlayState(Player, boolean)}.
     *
     * @return the newly created player object
     * @see #startPlayState(Player, boolean)
     */
    @NotNull
    public Player startPlayState(@NotNull PlayerConnection connection,
                                 @NotNull UUID uuid, @NotNull String username,
                                 boolean register) {
        final Player player = getPlayerProvider().createPlayer(uuid, username, connection);

        startPlayState(player, register);

        return player;
    }

    /**
     * Shutdowns the connection manager by kicking all the currently connected players.
     */
    public void shutdown() {
        DisconnectPacket disconnectPacket = new DisconnectPacket(getShutdownText());
        // Unsafe loop, ok because we are only sending a packet, which is a thread-safe operation
        for (Player player : getUnwrapOnlinePlayers()) {
            final PlayerConnection playerConnection = player.getPlayerConnection();
            if (playerConnection instanceof NettyPlayerConnection) {
                final NettyPlayerConnection nettyPlayerConnection = (NettyPlayerConnection) playerConnection;
                final Channel channel = nettyPlayerConnection.getChannel();
                channel.writeAndFlush(disconnectPacket);
                channel.close();
            }
        }
        this.acquirablePlayers.clear();
        this.players.clear();
        this.connectionPlayerMap.clear();
    }

    /**
     * Connects waiting players.
     */
    public void updateWaitingPlayers() {
        // Connect waiting players
        waitingPlayersTick();
    }

    /**
     * Updates keep alive by checking the last keep alive packet and send a new one if needed.
     *
     * @param tickStart the time of the update in milliseconds, forwarded to the packet
     */
    public void handleKeepAlive(long tickStart) {
        final KeepAlivePacket keepAlivePacket = new KeepAlivePacket(tickStart);

        // Unsafe loop, shouldn't create any issue since missing a keep alive tick
        // does not affect the overall server playability
        for (Player player : getUnwrapOnlinePlayers()) {
            final long lastKeepAlive = tickStart - player.getLastKeepAlive();
            if (lastKeepAlive > KEEP_ALIVE_DELAY && player.didAnswerKeepAlive()) {
                final PlayerConnection playerConnection = player.getPlayerConnection();
                player.refreshKeepAlive(tickStart);
                playerConnection.sendPacket(keepAlivePacket);
            } else if (lastKeepAlive >= KEEP_ALIVE_KICK) {
                player.kick(TIMEOUT_TEXT);
            }
        }
    }

    /**
     * Adds connected clients after the handshake (used to free the networking threads).
     */
    private void waitingPlayersTick() {
        Player waitingPlayer;
        while ((waitingPlayer = waitingPlayers.poll()) != null) {

            PlayerLoginEvent loginEvent = new PlayerLoginEvent(waitingPlayer);
            waitingPlayer.callEvent(PlayerLoginEvent.class, loginEvent);
            final Instance spawningInstance = loginEvent.getSpawningInstance();

            Check.notNull(spawningInstance, "You need to specify a spawning instance in the PlayerLoginEvent");

            waitingPlayer.UNSAFE_init(spawningInstance);

            // Spawn the player at Player#getRespawnPoint during the next instance tick
            spawningInstance.scheduleNextTick(waitingPlayer::setInstance);
        }
    }

    /**
     * Adds a player into the waiting list, to be handled during the next server tick.
     *
     * @param player the {@link Player player} to add into the waiting list
     */
    public void addWaitingPlayer(@NotNull Player player) {
        this.waitingPlayers.add(player);
    }
}
