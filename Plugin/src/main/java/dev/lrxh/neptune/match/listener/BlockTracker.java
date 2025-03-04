package dev.lrxh.neptune.match.listener;

import com.destroystokyo.paper.event.block.BlockDestroyEvent;
import dev.lrxh.neptune.API;
import dev.lrxh.neptune.match.Match;
import dev.lrxh.neptune.profile.impl.Profile;
import io.papermc.paper.event.block.BlockBreakBlockEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public class BlockTracker implements Listener {

    private final Map<UUID, Entity> crystalOwners = new HashMap<>();

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        getMatchForPlayer(player).ifPresent(match -> match.getChanges().put(event.getBlock().getLocation(), event.getBlockReplacedState().getBlockData()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        UUID uuid = event.getEntity().getOwnerUniqueId();
        if (uuid == null) return;
        Player player = Bukkit.getPlayer(uuid);
        if (player == null) return;
        getMatchForPlayer(player).ifPresent(match -> match.getEntities().add(event.getEntity()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCrystalPlace(EntitySpawnEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal crystal)) return;

        if (!event.getEntity().getEntitySpawnReason().equals(CreatureSpawnEvent.SpawnReason.DEFAULT)) return;

        Player player = getPlayer(crystal.getLocation());

        if (player == null) {
            event.setCancelled(true);
            return;
        }

        getMatchForPlayer(player).ifPresent(match -> match.getEntities().add(crystal));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof EnderCrystal && event.getDamager() instanceof Player player) {
            crystalOwners.put(player.getUniqueId(), event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeave(PlayerQuitEvent event) {
        crystalOwners.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplosion(EntityExplodeEvent event) {
        event.setYield(0);
        if (event.getEntity() instanceof EnderCrystal enderCrystal) {
            Player player = crystalOwners.get(enderCrystal.getUniqueId()) != null
                    ? Bukkit.getPlayer(crystalOwners.get(enderCrystal.getUniqueId()).getUniqueId()) : null;

            if (player == null) {
                event.setCancelled(true);
                return;
            }

            getMatchForPlayer(player).ifPresent(match -> {
                for (Block block : event.blockList()) {
                    block.getDrops().clear();

                    if (!match.getPlacedBlocks().contains(block.getLocation())) {
                        match.getChanges().put(block.getLocation(), block.getBlockData());
                    }
                }
            });

            crystalOwners.remove(player.getUniqueId());
        } else {
            Player player = getPlayer(event.getLocation());

            if (player == null) {
                event.setCancelled(true);
                return;
            }

            getMatchForPlayer(player).ifPresent(match -> {
                for (Block block : event.blockList()) {
                    block.getDrops().clear();

                    if (!match.getPlacedBlocks().contains(block.getLocation())) {
                        match.getChanges().put(block.getLocation(), block.getBlockData());
                    }
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Player player = event.getPlayer();
        getMatchForPlayer(player).ifPresent(match -> match.getLiquids().add(event.getBlock().getLocation()));
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        Player player = getPlayer(toBlock.getLocation());

        if (player == null) {
            event.setCancelled(true);
            return;
        }

        getMatchForPlayer(player).ifPresent(match -> {
            if (!match.getPlacedBlocks().contains(toBlock.getLocation())) {
                match.getChanges().put(toBlock.getLocation(), Material.AIR.createBlockData());
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakBlockEvent event) {
        event.getDrops().clear();
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDestroy(BlockDestroyEvent event) {
        Block block = event.getBlock();
        block.getDrops().clear();
        event.setWillDrop(false);
        Player player = getPlayer(block.getLocation());

        if (player == null) {
            event.setCancelled(true);
            return;
        }

        getMatchForPlayer(player).ifPresent(match -> {
            if (!match.getPlacedBlocks().contains(block.getLocation())) {
                match.getChanges().put(block.getLocation(), block.getBlockData());
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        getMatchForPlayer(player).ifPresent(match -> {
            if (!match.getPlacedBlocks().contains(event.getBlock().getLocation())) {
                match.getChanges().put(event.getBlock().getLocation(), event.getBlock().getBlockData());
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMultiPlace(BlockMultiPlaceEvent event) {
        Player player = event.getPlayer();
        getMatchForPlayer(player).ifPresent(match -> {
            for (BlockState blockState : event.getReplacedBlockStates()) {
                if (!match.getPlacedBlocks().contains(blockState.getLocation())) {
                    match.getChanges().put(blockState.getLocation(), blockState.getBlockData());
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplosionPrime(ExplosionPrimeEvent event) {
        event.setFire(false);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (event.getCause() == BlockIgniteEvent.IgniteCause.LIGHTNING || event.getCause() == BlockIgniteEvent.IgniteCause.FIREBALL || event.getCause() == BlockIgniteEvent.IgniteCause.EXPLOSION) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onExplode(BlockExplodeEvent event) {
        event.setYield(0);
        Player player = getPlayer(event.getBlock().getLocation());

        if (player == null) {
            event.setCancelled(true);
            return;
        }

        getMatchForPlayer(player).ifPresent(match -> {
            for (Block block : event.blockList()) {
                if (!match.getPlacedBlocks().contains(block.getLocation())) {
                    match.getChanges().put(block.getLocation(), block.getBlockData());
                }
            }
        });
    }


    private Player getPlayer(Location location) {
        Player player = null;

        for (Entity entity : location.getNearbyEntities(10, 10, 10)) {
            if (entity instanceof Player p) player = p;
        }

        return player;
    }

    private Optional<Match> getMatchForPlayer(Player player) {
        Profile profile = API.getProfile(player);
        return Optional.ofNullable(profile)
                .map(Profile::getMatch);
    }
}