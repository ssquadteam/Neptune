package dev.lrxh.neptune.game.match;

import dev.lrxh.neptune.API;
import dev.lrxh.neptune.Neptune;
import dev.lrxh.neptune.configs.impl.MessagesLocale;
import dev.lrxh.neptune.configs.impl.ScoreboardLocale;
import dev.lrxh.neptune.configs.impl.SettingsLocale;
import dev.lrxh.neptune.game.arena.Arena;
import dev.lrxh.neptune.game.kit.Kit;
import dev.lrxh.neptune.game.kit.impl.KitRule;
import dev.lrxh.neptune.game.match.impl.FfaFightMatch;
import dev.lrxh.neptune.game.match.impl.MatchState;
import dev.lrxh.neptune.game.match.impl.SoloFightMatch;
import dev.lrxh.neptune.game.match.impl.participant.DeathCause;
import dev.lrxh.neptune.game.match.impl.participant.Participant;
import dev.lrxh.neptune.game.match.impl.participant.ParticipantColor;
import dev.lrxh.neptune.game.match.impl.team.TeamFightMatch;
import dev.lrxh.neptune.profile.data.ProfileState;
import dev.lrxh.neptune.profile.impl.Profile;
import dev.lrxh.neptune.providers.clickable.Replacement;
import dev.lrxh.neptune.providers.placeholder.PlaceholderUtil;
import dev.lrxh.neptune.utils.BlockChanger;
import dev.lrxh.neptune.utils.CC;
import dev.lrxh.neptune.utils.PlayerUtil;
import dev.lrxh.neptune.utils.Time;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;
import net.kyori.adventure.text.Component;
import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.LinkedHashMap;
import java.util.Map.Entry;
import java.util.stream.Collectors;
import java.util.concurrent.atomic.AtomicInteger;

@AllArgsConstructor
@Getter
@Setter
public abstract class Match {
    public final List<UUID> spectators = new ArrayList<>();
    public final Neptune plugin = Neptune.get();
    private final UUID uuid = UUID.randomUUID();
    private final HashSet<Location> placedBlocks = new HashSet<>();
    
    // Modified block tracking system with chunking
    private final Map<ChunkKey, Map<BlockPosition, BlockData>> chunkedChanges = new ConcurrentHashMap<>();
    private final Set<Location> liquids = new HashSet<>();
    private final HashSet<Entity> entities = new HashSet<>();
    private final Time time = new Time();
    // Add a participant lookup map for faster access
    private final Map<UUID, Participant> participantLookup = new HashMap<>();
    // Track block change statistics
    private int totalBlockChanges = 0;
    private int totalLiquidChanges = 0;
    public MatchState state;
    public Arena arena;
    public Kit kit;
    public List<Participant> participants;
    public int rounds;
    private boolean duel;
    private boolean ended;
    
    // Add participant interaction tracking
    private final Map<UUID, Map<UUID, Integer>> damageDealt = new HashMap<>();
    private final Map<UUID, Integer> arrowsHit = new HashMap<>();
    private final Map<UUID, Integer> arrowsShot = new HashMap<>();
    private final Map<UUID, Long> lastDamageTime = new HashMap<>();
    
    /**
     * Compatibility constructor that matches what subclasses expect
     * 
     * @param state The match state
     * @param arena The arena
     * @param kit The kit
     * @param participants The participants
     * @param rounds The number of rounds
     * @param duel Whether this is a duel
     * @param ended Whether the match has ended
     */
    public Match(MatchState state, Arena arena, Kit kit, List<Participant> participants, int rounds, boolean duel, boolean ended) {
        this.state = state;
        this.arena = arena;
        this.kit = kit;
        this.participants = participants;
        this.rounds = rounds;
        this.duel = duel;
        this.ended = ended;
    }
    
    /**
     * Inner class representing a chunk key for efficient block storage
     */
    @Getter
    @AllArgsConstructor
    public static class ChunkKey {
        private final int x;
        private final int z;
        
        /**
         * Create a ChunkKey from a location
         * 
         * @param location The location to create a key from
         * @return A new ChunkKey
         */
        public static ChunkKey fromLocation(Location location) {
            return new ChunkKey(location.getBlockX() >> 4, location.getBlockZ() >> 4);
        }
        
        /**
         * Get the world for this chunk key
         * 
         * @return The world
         */
        public World getWorld() {
            // This needs to be implemented based on how the world is stored
            // For now, we'll assume the arena world
            return null;
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            ChunkKey chunkKey = (ChunkKey) o;
            return x == chunkKey.x && z == chunkKey.z;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(x, z);
        }
    }
    
    /**
     * Inner class representing a block position
     */
    @Getter
    @AllArgsConstructor
    public static class BlockPosition {
        public final int x;
        public final int y;
        public final int z;
        
        /**
         * Create a BlockPosition from a location
         * 
         * @param location The location to create a position from
         * @return A new BlockPosition
         */
        public static BlockPosition fromLocation(Location location) {
            return new BlockPosition(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }
        
        /**
         * Convert this position to a location in the given world
         * 
         * @param world The world to create the location in
         * @return A new Location
         */
        public Location toLocation(World world) {
            return new Location(world, x, y, z);
        }
        
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            BlockPosition that = (BlockPosition) o;
            return x == that.x && y == that.y && z == that.z;
        }
        
        @Override
        public int hashCode() {
            return Objects.hash(x, y, z);
        }
    }
    
    /**
     * Class to track performance metrics for the match
     */
    public class PerformanceTracker {
        private long startTimeNanos;
        private long blockChangesProcessed = 0;
        private long chunkUpdatesProcessed = 0;
        private long lastLogTimeMillis = 0;
        private static final long LOG_INTERVAL_MILLIS = 5000; // Log every 5 seconds
        
        /**
         * Start tracking performance
         */
        public void start() {
            startTimeNanos = System.nanoTime();
            lastLogTimeMillis = System.currentTimeMillis();
        }
        
        /**
         * Track a block change
         */
        public void trackBlockChange() {
            blockChangesProcessed++;
        }
        
        /**
         * Track a chunk update
         */
        public void trackChunkUpdate() {
            chunkUpdatesProcessed++;
        }
        
        /**
         * Log current performance statistics if enough time has passed
         */
        public void logIfNeeded() {
            long currentTimeMillis = System.currentTimeMillis();
            if (currentTimeMillis - lastLogTimeMillis > LOG_INTERVAL_MILLIS) {
                log();
                lastLogTimeMillis = currentTimeMillis;
            }
        }
        
        /**
         * Log the current performance statistics
         */
        public void log() {
            long elapsedNanos = System.nanoTime() - startTimeNanos;
            double elapsedSeconds = elapsedNanos / 1_000_000_000.0;
            double blocksPerSecond = blockChangesProcessed / elapsedSeconds;
            double chunksPerSecond = chunkUpdatesProcessed / elapsedSeconds;
            
            plugin.getLogger().info(String.format(
                "Performance: Processed %d blocks in %d chunks (%.2f blocks/sec, %.2f chunks/sec)",
                blockChangesProcessed, chunkUpdatesProcessed, blocksPerSecond, chunksPerSecond
            ));
        }
        
        /**
         * Reset the performance tracker
         */
        public void reset() {
            startTimeNanos = System.nanoTime();
            blockChangesProcessed = 0;
            chunkUpdatesProcessed = 0;
            lastLogTimeMillis = System.currentTimeMillis();
        }
    }
    
    // Performance tracker instance
    private final PerformanceTracker performanceTracker = new PerformanceTracker();
    
    /**
     * Track a block change with performance monitoring
     * 
     * @param loc The location of the block
     * @param blockData The block data
     */
    public void trackBlockChange(Location loc, BlockData blockData) {
        ChunkKey chunkKey = ChunkKey.fromLocation(loc);
        BlockPosition pos = BlockPosition.fromLocation(loc);
        
        Map<BlockPosition, BlockData> chunkMap = chunkedChanges.computeIfAbsent(chunkKey, k -> {
            performanceTracker.trackChunkUpdate();
            return new HashMap<>();
        });
        
        if (!chunkMap.containsKey(pos)) {
            performanceTracker.trackBlockChange();
            totalBlockChanges++;
        }
        
        chunkMap.put(pos, blockData);
        
        // Log performance stats occasionally during heavy operations
        performanceTracker.logIfNeeded();
    }
    
    /**
     * Track a liquid block for reset
     * 
     * @param location The location of the liquid block
     */
    public void trackLiquid(Location location) {
        liquids.add(location);
        totalLiquidChanges++;
    }
    
    /**
     * Get statistics about the blocks changed in this match
     * 
     * @return A map containing statistics about block changes
     */
    public Map<String, Integer> getBlockChangeStats() {
        Map<String, Integer> stats = new HashMap<>();
        stats.put("totalBlockChanges", totalBlockChanges);
        stats.put("totalLiquidChanges", totalLiquidChanges);
        stats.put("totalChunksAffected", chunkedChanges.size());
        stats.put("totalEntities", entities.size());
        return stats;
    }
    
    /**
     * Reset the arena asynchronously with improved tracking and stats
     */
    public void resetArena() {
        if (arena == null) return;
        
        // Start performance tracking
        performanceTracker.reset();
        performanceTracker.start();
        
        // Gather all blocks that need to be reset
        List<BlockChanger.BlockSnapshot> blocks = new ArrayList<>();
        
        // Add liquid blocks to reset list
        for (Location location : liquids) {
            blocks.add(new BlockChanger.BlockSnapshot(location, Bukkit.createBlockData(Material.AIR)));
        }
        
        // Add changed blocks to reset list using chunked system
        for (Map.Entry<ChunkKey, Map<BlockPosition, BlockData>> entry : chunkedChanges.entrySet()) {
            World world = arena.getWorld();
            for (Map.Entry<BlockPosition, BlockData> blockEntry : entry.getValue().entrySet()) {
                BlockPosition pos = blockEntry.getKey();
                BlockData data = blockEntry.getValue();
                
                Location location = new Location(world, pos.x, pos.y, pos.z);
                blocks.add(new BlockChanger.BlockSnapshot(location, data));
            }
        }
        
        // Log statistics before reset
        if (!blocks.isEmpty()) {
            plugin.getLogger().info("Resetting arena with " + blocks.size() + " blocks across " + 
                                   chunkedChanges.size() + " chunks");
        }
        
        // Track final performance metrics
        performanceTracker.log();
        
        // Reset blocks asynchronously
        BlockChanger.setBlocksAsync(arena.getWorld(), blocks)
                .thenRun(() -> {
                    // Clear tracking data after reset is complete
                    chunkedChanges.clear();
                    liquids.clear();
                    placedBlocks.clear();
                    totalBlockChanges = 0;
                    totalLiquidChanges = 0;
                    
                    // Log completion on the main thread
                    Bukkit.getScheduler().runTask(plugin, () -> 
                        plugin.getLogger().info("Arena reset complete"));
                });
        
        // Clear entities immediately, don't wait for block reset
        removeEntities();
    }
    
    /**
     * Remove all entities from the arena
     */
    public void removeEntities() {
        for (Entity entity : entities) {
            entity.remove();
        }
        entities.clear();
    }
    
    /**
     * Checks if a location is protected from block placement/breaking due to being near an end portal
     * Used for portal goal kits to prevent griefing near portals
     * 
     * @param location The location to check
     * @return True if protected, false if not
     */
    public boolean isLocationPortalProtected(Location location) {
        // Only check if the kit has bridges enabled
        if (kit.is(KitRule.BRIDGES)) {
            // Get protection radius from kit (if PORTAL_PROTECTION_RADIUS is enabled) or use default
            int protectionRadius = kit.is(KitRule.PORTAL_PROTECTION_RADIUS) ? 
                    kit.getPortalProtectionRadius() : 3;
            
            // If radius is 0, portal protection is disabled
            if (protectionRadius <= 0) {
                return false;
            }
                    
            // Get the blocks around the location
            for (int x = -protectionRadius; x <= protectionRadius; x++) {
                for (int y = -protectionRadius; y <= protectionRadius; y++) {
                    for (int z = -protectionRadius; z <= protectionRadius; z++) {
                        Location checkLoc = location.clone().add(x, y, z);
                        if (checkLoc.getBlock().getType() == Material.END_PORTAL) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }
    
    public List<String> getScoreboard(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null) return new ArrayList<>();
        
        // Check global in-game scoreboard setting
        if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME.getBoolean()) return new ArrayList<>();

        if (this instanceof SoloFightMatch) {
            MatchState matchState = this.getState();

            if (kit.is(KitRule.BEST_OF_ROUNDS) && matchState.equals(MatchState.STARTING)) {
                if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME_BESTOF.getBoolean()) return new ArrayList<>();
                return PlaceholderUtil.format(new ArrayList<>(ScoreboardLocale.IN_GAME_BEST_OF.getStringList()), player);
            }

            switch (matchState) {
                case STARTING:
                    if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME_STARTING.getBoolean()) return new ArrayList<>();
                    return PlaceholderUtil.format(new ArrayList<>(ScoreboardLocale.IN_GAME_STARTING.getStringList()), player);
                case IN_ROUND:
                    if (this.getRounds() > 1) {
                        if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME_BESTOF.getBoolean()) return new ArrayList<>();
                        return PlaceholderUtil.format(new ArrayList<>(ScoreboardLocale.IN_GAME_BEST_OF.getStringList()), player);
                    }
                    if (this.getKit().is(KitRule.BOXING)) {
                        if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME_BOXING.getBoolean()) return new ArrayList<>();
                        return PlaceholderUtil.format(new ArrayList<>(ScoreboardLocale.IN_GAME_BOXING.getStringList()), player);
                    }
                    if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME_REGULAR.getBoolean()) return new ArrayList<>();
                    return PlaceholderUtil.format(new ArrayList<>(ScoreboardLocale.IN_GAME.getStringList()), player);
                case ENDING:
                    if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME_ENDED.getBoolean()) return new ArrayList<>();
                    return PlaceholderUtil.format(new ArrayList<>(ScoreboardLocale.IN_GAME_ENDED.getStringList()), player);
                default:
                    break;
            }
        } else if (this instanceof TeamFightMatch) {
            if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME_TEAM.getBoolean()) return new ArrayList<>();
            return PlaceholderUtil.format(new ArrayList<>(ScoreboardLocale.IN_GAME_TEAM.getStringList()), player);
        } else if (this instanceof FfaFightMatch) {
            if (!SettingsLocale.ENABLED_SCOREBOARD_INGAME_FFA.getBoolean()) return new ArrayList<>();
            return PlaceholderUtil.format(new ArrayList<>(ScoreboardLocale.IN_GAME_FFA.getStringList()), player);
        }

        return null;
    }

    public void removeSpectator(UUID playerUUID, boolean sendMessage) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null) return;
        Profile profile = API.getProfile(playerUUID);

        if (profile.getMatch() == null) return;
        profile.setState(ProfileState.IN_LOBBY);
        PlayerUtil.reset(player);
        PlayerUtil.teleportToSpawn(playerUUID);
        profile.setMatch(null);

        spectators.remove(playerUUID);

        if (sendMessage) {
            broadcast(MessagesLocale.SPECTATE_STOP, new Replacement("<player>", player.getName()));
        }
    }

    public void setupPlayer(UUID playerUUID) {
        Player player = Bukkit.getPlayer(playerUUID);
        if (player == null) return;
        Profile profile = API.getProfile(playerUUID);
        profile.setMatch(this);
        profile.setState(ProfileState.IN_GAME);
        PlayerUtil.reset(player);
        Participant participant = getParticipant(playerUUID);
        participant.setLastAttacker(null);
        kit.giveLoadout(participant);
    }

    public void broadcast(MessagesLocale messagesLocale, Replacement... replacements) {
        forEachParticipant(participant -> messagesLocale.send(participant.getPlayerUUID(), replacements));

        forEachSpectator(player -> messagesLocale.send(player.getUniqueId(), replacements));
    }

    public void broadcast(String message) {
        forEachParticipant(participant -> participant.sendMessage(message));

        forEachSpectator(player -> player.sendMessage(CC.color(message)));
    }

    public void checkRules() {
        forEachParticipant(participant -> {
            if (!(this instanceof FfaFightMatch)) {
                if (kit.is(KitRule.DENY_MOVEMENT)) {
                    participant.toggleFreeze();
                }
            }
            if (kit.is(KitRule.SHOW_HP)) {
                if (state.equals(MatchState.STARTING)) {
                    showHealth();
                }
            }

            if (!kit.is(KitRule.SATURATION)) {
                Player player = participant.getPlayer();
                if (player == null) return;
                player.setSaturation(0.0F);
            } else {
                Player player = participant.getPlayer();
                if (player == null) return;
                player.setSaturation(20.0f);
            }
        });

        forEachPlayer(player -> {
            Profile profile = API.getProfile(player);
            profile.handleVisibility();
        });
    }

    public void playSound(Sound sound) {
        forEachPlayer(player -> player.playSound(player.getLocation(), sound, 1.0f, 1.0f));
    }

    public Location getSpawn(Participant participant) {
        if (participant.getColor().equals(ParticipantColor.RED)) {
            return arena.getRedSpawn();
        } else {
            return arena.getBlueSpawn();
        }
    }

    public List<Player> getPlayers() {
        List<Player> players = new ArrayList<>();

        for (Participant participant : participants) {
            players.add(participant.getPlayer());
        }

        return players;
    }

    /**
     * Initialize participant lookup map for faster access
     * This should be called after setting the participants list
     */
    protected void initParticipantLookup() {
        participantLookup.clear();
        if (participants != null) {
            for (Participant participant : participants) {
                participantLookup.put(participant.getPlayerUUID(), participant);
            }
        }
    }

    public Participant getParticipant(UUID playerUUID) {
        return participantLookup.get(playerUUID);
    }

    public Participant getParticipant(Player player) {
        return player != null ? participantLookup.get(player.getUniqueId()) : null;
    }

    public void sendTitle(String header, String footer, int duration) {
        forEachParticipant(participant -> PlayerUtil.sendTitle(participant.getPlayer(), header, footer, duration));
    }

    public void sendMessage(MessagesLocale message, Replacement... replacements) {
        forEachParticipant(participant -> message.send(participant.getPlayerUUID(), replacements));
    }

    public void addSpectator(Player player, Player target, boolean sendMessage, boolean add) {
        Profile profile = API.getProfile(player);

        profile.setMatch(this);
        profile.setState(ProfileState.IN_SPECTATOR);
        if (add) spectators.add(player.getUniqueId());

        forEachPlayer(participiantPlayer -> player.showPlayer(Neptune.get(), participiantPlayer));

        if (sendMessage) {
            broadcast(MessagesLocale.SPECTATE_START, new Replacement("<player>", player.getName()));
        }

        player.teleport(target.getLocation());
        player.setGameMode(GameMode.SPECTATOR);
    }

    public void showPlayerForSpectators() {
        forEachSpectator(player -> forEachPlayer(participiantPlayer -> player.showPlayer(Neptune.get(), participiantPlayer)));
    }

    public void forEachPlayer(Consumer<Player> action) {
        for (Participant participant : participants) {
            Player player = participant.getPlayer();
            if (player != null) {
                action.accept(player);
            }
        }
    }

    public void forEachSpectator(Consumer<Player> action) {
        for (UUID spectatorUUID : spectators) {
            Player player = Bukkit.getPlayer(spectatorUUID);
            if (player != null) {
                action.accept(player);
            }
        }
    }

    public void forEachParticipant(Consumer<Participant> action) {
        for (Participant participant : participants) {
            if (participant.isDisconnected() || participant.isLeft()) continue;
            Player player = participant.getPlayer();
            if (player != null) {
                action.accept(participant);
            }
        }
    }

    public void forEachParticipantForce(Consumer<Participant> action) {
        for (Participant participant : participants) {
            Player player = participant.getPlayer();
            if (player != null) {
                action.accept(participant);
            }
        }
    }

    public void hideHealth() {
        forEachPlayer(player -> {
            Objective objective = player.getScoreboard().getObjective(DisplaySlot.BELOW_NAME);
            if (objective != null) {
                objective.unregister();
            }
        });
    }

    public void hideParticipant(Participant participant) {
        forEachParticipant(p -> {
            if (!p.equals(participant)) {
                p.getPlayer().hidePlayer(Neptune.get(), participant.getPlayer());
            }
        });
    }

    public void showParticipant(Participant participant) {
        forEachParticipant(p -> {
            if (!p.equals(participant)) {
                p.getPlayer().showPlayer(Neptune.get(), participant.getPlayer());
            }
        });
    }

    private void showHealth() {
        forEachPlayer(player -> {
            Objective objective = player.getScoreboard().getObjective(DisplaySlot.BELOW_NAME);

            if (objective == null) {
                objective = player.getScoreboard().registerNewObjective("neptune_health", Criteria.HEALTH, Component.text(CC.color("&c❤")));
            }
            try {
                objective.setDisplaySlot(DisplaySlot.BELOW_NAME);
            } catch (IllegalStateException ignored) {
            }

            player.sendHealthUpdate();
        });
    }

    public void teleportToPositions() {
        // This method is called when a score happens in Bridges mode
        // It teleports players back to their spawn positions and resets their inventories
        for (Participant participant : participants) {
            teleportPlayerToPosition(participant);
        }
    }

    public void teleportPlayerToPosition(Participant participant) {
        Player player = participant.getPlayer();
        if (player == null) return;
        
        // Always reset player inventory for Bridges mode when a point is scored
        boolean isBridges = kit.is(KitRule.BRIDGES);
        if (isBridges) {
            // Reset player's inventory
            player.getInventory().clear();
            player.getInventory().setArmorContents(null);
            
            // Reset health and saturation
            player.setHealth(player.getMaxHealth());
            player.setFoodLevel(20);
            player.setSaturation(20.0f);
            
            // Clear any potion effects
            player.getActivePotionEffects().forEach(effect -> 
                player.removePotionEffect(effect.getType()));
            
            // Give kit loadout again
            kit.giveLoadout(participant);
        }
        
        // Teleport to appropriate spawn
        if (participant.getColor().equals(ParticipantColor.RED)) {
            player.teleport(arena.getRedSpawn());
        } else {
            player.teleport(arena.getBlueSpawn());
        }
        
        // Update inventory to ensure changes are visible to the player
        if (isBridges) {
            player.updateInventory();
        }
    }

    public abstract void end(Participant loser);

    public abstract void onDeath(Participant participant);

    public abstract void onLeave(Participant participant, boolean quit);

    public abstract void startMatch();

    public abstract void sendEndMessage();

    public abstract void breakBed(Participant participant);

    public abstract void sendTitle(Participant participant, String header, String footer, int duration);

    /**
     * Setup participants with initial state and teleport them to their positions
     */
    public void setupParticipants() {
        // Initialize the lookup map for fast access
        initParticipantLookup();
        
        // Reset tracking maps
        damageDealt.clear();
        arrowsHit.clear();
        arrowsShot.clear();
        lastDamageTime.clear();
        
        // Setup each player with their initial state
        forEachParticipant(participant -> {
            Player player = participant.getPlayer();
            if (player == null) return;
            
            // Initialize tracking maps for this player
            damageDealt.put(player.getUniqueId(), new HashMap<>());
            arrowsHit.put(player.getUniqueId(), 0);
            arrowsShot.put(player.getUniqueId(), 0);
            
            // Reset participant state
            participant.setLastAttacker(null);
            participant.setDeathCause(null);
            participant.setCombo(0);
            participant.setHits(0);
            
            // Reset player state
            PlayerUtil.reset(player);
            
            // Apply kit
            kit.giveLoadout(participant);
        });
        
        // Apply any match-specific rules
        checkRules();
        
        // Schedule periodic tasks if needed
        scheduleHealthUpdates();
    }
    
    /**
     * Schedule health updates for participants if health display is enabled
     */
    protected void scheduleHealthUpdates() {
        if (kit.is(KitRule.SHOW_HP)) {
            Bukkit.getScheduler().runTaskTimer(plugin, this::showHealth, 20L, 20L);
        }
    }
    
    /**
     * Track damage between participants for statistics
     * 
     * @param attacker The attacking participant
     * @param victim The victim participant
     * @param damage The amount of damage dealt
     */
    public void trackDamage(Participant attacker, Participant victim, double damage) {
        if (attacker == null || victim == null) return;
        
        UUID attackerUUID = attacker.getPlayerUUID();
        UUID victimUUID = victim.getPlayerUUID();
        
        // Update damage tracking
        Map<UUID, Integer> attackerDamage = damageDealt.computeIfAbsent(attackerUUID, k -> new HashMap<>());
        int currentDamage = attackerDamage.getOrDefault(victimUUID, 0);
        attackerDamage.put(victimUUID, currentDamage + (int)damage);
        
        // Update last damage time
        lastDamageTime.put(victimUUID, System.currentTimeMillis());
        
        // Set last attacker
        victim.setLastAttacker(attacker);
    }
    
    /**
     * Track an arrow hit for statistics
     * 
     * @param shooter The UUID of the player who shot the arrow
     */
    public void trackArrowHit(UUID shooter) {
        arrowsHit.put(shooter, arrowsHit.getOrDefault(shooter, 0) + 1);
    }
    
    /**
     * Track an arrow shot for statistics
     * 
     * @param shooter The UUID of the player who shot the arrow
     */
    public void trackArrowShot(UUID shooter) {
        arrowsShot.put(shooter, arrowsShot.getOrDefault(shooter, 0) + 1);
    }
    
    /**
     * Get arrow accuracy for a participant
     * 
     * @param uuid The UUID of the participant
     * @return The arrow accuracy as a percentage (0-100)
     */
    public int getArrowAccuracy(UUID uuid) {
        int shot = arrowsShot.getOrDefault(uuid, 0);
        if (shot == 0) return 0;
        
        int hit = arrowsHit.getOrDefault(uuid, 0);
        return (int)(((double)hit / shot) * 100);
    }
    
    /**
     * Get total damage dealt by a participant
     * 
     * @param uuid The UUID of the participant
     * @return The total damage dealt
     */
    public int getTotalDamageDealt(UUID uuid) {
        Map<UUID, Integer> playerDamage = damageDealt.get(uuid);
        if (playerDamage == null) return 0;
        
        return playerDamage.values().stream().mapToInt(Integer::intValue).sum();
    }
    
    /**
     * Get match statistics for a participant
     * 
     * @param uuid The UUID of the participant
     * @return A map of statistics
     */
    public Map<String, Object> getParticipantStats(UUID uuid) {
        Map<String, Object> stats = new HashMap<>();
        Participant participant = getParticipant(uuid);
        
        if (participant == null) return stats;
        
        stats.put("hits", participant.getHits());
        stats.put("combo", participant.getCombo());
        stats.put("arrowsShot", arrowsShot.getOrDefault(uuid, 0));
        stats.put("arrowsHit", arrowsHit.getOrDefault(uuid, 0));
        stats.put("arrowAccuracy", getArrowAccuracy(uuid));
        stats.put("totalDamageDealt", getTotalDamageDealt(uuid));
        
        return stats;
    }
    
    /**
     * Send death message for a participant with enhanced statistics
     * 
     * @param deadParticipant The participant who died
     */
    public void sendDeathMessage(Participant deadParticipant) {
        String deathMessage = deadParticipant.getDeathMessage();
        DeathCause deathCause = deadParticipant.getDeathCause();
        
        // If we have a custom death message, use it
        if (!deathMessage.isEmpty()) {
            broadcast(deathMessage);
            return;
        }
        
        // If we have a death cause with default message, use it
        if (deathCause != null) {
            // Get the killer's statistics if this was a kill
            if (deathCause == DeathCause.KILL && deadParticipant.getLastAttacker() != null) {
                Participant killer = deadParticipant.getLastAttacker();
                Player killerPlayer = killer.getPlayer();
                
                if (killerPlayer != null) {
                    // Play kill sound
                    killerPlayer.playSound(killerPlayer.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
                    
                    // Get some interesting stats for the death message
                    int accuracy = getArrowAccuracy(killer.getPlayerUUID());
                    int combo = killer.getCombo();
                    
                    // Enhanced death message with stats if they're interesting
                    if (arrowsHit.getOrDefault(killer.getPlayerUUID(), 0) > 3 && accuracy > 50) {
                        // Use the bow kill message if available, or fall back to standard kill message
                        MessagesLocale bowKillMessage = getMessageLocale("KILL_BOW");
                        if (bowKillMessage != null) {
                            broadcast(bowKillMessage, 
                                new Replacement("<player>", deadParticipant.getNameColored()),
                                new Replacement("<killer>", killer.getNameColored()),
                                new Replacement("<accuracy>", String.valueOf(accuracy))
                            );
                        } else {
                            broadcast(deathCause.getMessagesLocale(),
                                new Replacement("<player>", deadParticipant.getNameColored()),
                                new Replacement("<killer>", killer.getNameColored())
                            );
                        }
                    } else if (combo > 3) {
                        // Use the combo kill message if available, or fall back to standard kill message
                        MessagesLocale comboKillMessage = getMessageLocale("KILL_COMBO");
                        if (comboKillMessage != null) {
                            broadcast(comboKillMessage, 
                                new Replacement("<player>", deadParticipant.getNameColored()),
                                new Replacement("<killer>", killer.getNameColored()),
                                new Replacement("<combo>", String.valueOf(combo))
                            );
                        } else {
                            broadcast(deathCause.getMessagesLocale(),
                                new Replacement("<player>", deadParticipant.getNameColored()),
                                new Replacement("<killer>", killer.getNameColored())
                            );
                        }
                    } else {
                        // Default kill message
                        broadcast(deathCause.getMessagesLocale(),
                            new Replacement("<player>", deadParticipant.getNameColored()),
                            new Replacement("<killer>", killer.getNameColored())
                        );
                    }
                    return;
                }
            }
            
            // Default death message
            broadcast(
                deathCause.getMessagesLocale(),
                new Replacement("<player>", deadParticipant.getNameColored()),
                new Replacement("<killer>", deadParticipant.getLastAttackerName())
            );
        } else {
            // Unknown death cause - use generic death message
            MessagesLocale unknownDeathMessage = getMessageLocale("DEATH_UNKNOWN");
            if (unknownDeathMessage != null) {
                broadcast(unknownDeathMessage, 
                    new Replacement("<player>", deadParticipant.getNameColored())
                );
            } else {
                // Fall back to a generic message if the specific locale is not available
                broadcast(CC.color("&c" + deadParticipant.getNameColored() + " &7died"));
            }
        }
    }

    /**
     * Safely get a MessagesLocale by name, returning null if not found
     * Used to handle potentially missing message locale constants
     * 
     * @param name The name of the message locale constant
     * @return The MessagesLocale or null if not found
     */
    private MessagesLocale getMessageLocale(String name) {
        try {
            return MessagesLocale.valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Backward compatibility method for BlockTracker
     * Checks if a location has been changed
     * 
     * @param location The location to check
     * @return true if the location has been changed
     */
    public boolean hasBlockChange(Location location) {
        ChunkKey chunkKey = ChunkKey.fromLocation(location);
        BlockPosition blockPos = BlockPosition.fromLocation(location);
        
        Map<BlockPosition, BlockData> chunkChanges = chunkedChanges.get(chunkKey);
        return chunkChanges != null && chunkChanges.containsKey(blockPos);
    }
    
    /**
     * Backward compatibility method for BlockTracker
     * Adds a block change to tracking
     * 
     * @param location The location of the block
     * @param blockData The original block data
     */
    public void addBlockChange(Location location, BlockData blockData) {
        trackBlockChange(location, blockData);
    }
}
