package dev.lrxh.neptune.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.plugin.Plugin;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class BlockChanger {
    private static Plugin plugin;
    private static boolean fawe = false;

    public static void load(Plugin plugin, boolean fawe) {
        BlockChanger.plugin = plugin;
        BlockChanger.fawe = fawe;
    }

    @AllArgsConstructor
    @Getter
    public static class BlockSnapshot {
        private final Location location;
        private final BlockData blockData;

        public BlockSnapshot(Location location, Material material) {
            this.location = location;
            this.blockData = material.createBlockData();
        }
    }

    /**
     * Enhanced asynchronous block setting with chunk-based optimization.
     * This method processes blocks in batches grouped by chunk to improve performance.
     *
     * @param world The world to set blocks in
     * @param blockSnapshots The list of block snapshots to apply
     * @return A CompletableFuture that completes when all blocks are set
     */
    public static CompletableFuture<Void> setBlocksAsync(World world, List<BlockSnapshot> blockSnapshots) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        if (blockSnapshots.isEmpty()) {
            future.complete(null);
            return future;
        }
        
        // Organize blocks by chunk
        Map<Long, List<BlockSnapshot>> blocksByChunk = new HashMap<>();
        
        for (BlockSnapshot snapshot : blockSnapshots) {
            Location loc = snapshot.getLocation();
            int chunkX = loc.getBlockX() >> 4;
            int chunkZ = loc.getBlockZ() >> 4;
            long chunkKey = ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
            
            blocksByChunk.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(snapshot);
        }
        
        // Metrics
        AtomicInteger chunksProcessed = new AtomicInteger(0);
        AtomicInteger blocksProcessed = new AtomicInteger(0);
        final int totalChunks = blocksByChunk.size();
        final int totalBlocks = blockSnapshots.size();
        
        // Create a queue of chunk operations
        Queue<Map.Entry<Long, List<BlockSnapshot>>> chunkQueue = 
                new ConcurrentLinkedQueue<>(blocksByChunk.entrySet());
        
        final int BATCH_SIZE = 3; // Number of chunks to process per tick
        final AtomicBoolean isRunning = new AtomicBoolean(false);
        
        // Process chunks in batches
        Runnable processChunks = new Runnable() {
            @Override
            public void run() {
                // If another instance is already running, return
                if (!isRunning.compareAndSet(false, true)) {
                    return;
                }
                
                try {
                    // Process BATCH_SIZE chunks per tick
                    for (int i = 0; i < BATCH_SIZE && !chunkQueue.isEmpty(); i++) {
                        Map.Entry<Long, List<BlockSnapshot>> entry = chunkQueue.poll();
                        if (entry == null) continue;
                        
                        List<BlockSnapshot> chunkBlocks = entry.getValue();
                        
                        // Process blocks for this chunk
                        for (BlockSnapshot snapshot : chunkBlocks) {
                            try {
                                Location loc = snapshot.getLocation();
                                world.getBlockAt(loc).setBlockData(snapshot.getBlockData(), false);
                                blocksProcessed.incrementAndGet();
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error setting block: " + e.getMessage());
                            }
                        }
                        
                        chunksProcessed.incrementAndGet();
                    }
                    
                    // If queue is empty, complete the future
                    if (chunkQueue.isEmpty()) {
                        plugin.getLogger().info(String.format(
                            "Arena reset complete: %d/%d chunks processed, %d/%d blocks reset",
                            chunksProcessed.get(), totalChunks,
                            blocksProcessed.get(), totalBlocks
                        ));
                        future.complete(null);
                    } else {
                        // Schedule the next batch
                        Bukkit.getScheduler().runTaskLater(plugin, this, 1L);
                    }
                } finally {
                    isRunning.set(false);
                }
            }
        };
        
        // Start processing
        Bukkit.getScheduler().runTask(plugin, processChunks);
        
        return future;
    }

    /**
     * Legacy method for compatibility - uses the enhanced async implementation
     */
    public static void setBlocks(World world, List<BlockSnapshot> blockSnapshots) {
        setBlocksAsync(world, blockSnapshots);
    }
}