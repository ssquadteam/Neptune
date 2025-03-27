package dev.lrxh.neptune.utils;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.scheduler.BukkitRunnable;

import dev.lrxh.neptune.Neptune;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * Utility class for efficiently changing blocks in batches,
 * particularly useful for arena resets and construction.
 */
public class BlockChanger {
    // Static reference to the plugin for scheduling tasks
    private static Neptune plugin;
    // Whether to use FastAsyncWorldEdit if available
    private static boolean fawe = false;
    
    /**
     * Load the BlockChanger with necessary dependencies
     * 
     * @param plugin The Neptune plugin instance
     * @param fawe Whether FastAsyncWorldEdit is available
     */
    public static void load(Neptune plugin, boolean fawe) {
        BlockChanger.plugin = plugin;
        BlockChanger.fawe = fawe;
        
        // Log initialization
        plugin.getLogger().info("BlockChanger initialized" + (fawe ? " with FastAsyncWorldEdit support" : ""));
    }

    /**
     * Inner class representing a snapshot of a block - its location and data.
     */
    @Getter
    @AllArgsConstructor
    public static class BlockSnapshot {
        private final Location location;
        private final BlockData blockData;
        
        /**
         * Create a BlockSnapshot with a material instead of BlockData
         * For backward compatibility
         * 
         * @param location The location
         * @param material The material
         */
        public BlockSnapshot(Location location, Material material) {
            this.location = location;
            this.blockData = Bukkit.createBlockData(material);
        }
    }
    
    /**
     * Legacy Snapshot class for backward compatibility
     */
    @Getter
    public static class Snapshot {
        private final Map<Location, BlockData> blocks = new HashMap<>();
        
        /**
         * Add a block to the snapshot
         * 
         * @param location The location
         * @param blockData The block data
         */
        public void addBlock(Location location, BlockData blockData) {
            blocks.put(location, blockData);
        }
        
        /**
         * Apply the snapshot
         * 
         * @return CompletableFuture that completes when the snapshot is applied
         */
        public CompletableFuture<Void> apply() {
            List<BlockSnapshot> snapshots = new ArrayList<>();
            
            // Convert to BlockSnapshot format
            for (Map.Entry<Location, BlockData> entry : blocks.entrySet()) {
                snapshots.add(new BlockSnapshot(entry.getKey(), entry.getValue()));
            }
            
            // If we have blocks to restore, use the enhanced async implementation
            if (!snapshots.isEmpty() && snapshots.get(0).getLocation() != null) {
                return setBlocksAsync(snapshots.get(0).getLocation().getWorld(), snapshots);
            }
            
            // Nothing to restore
            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Paste a snapshot asynchronously with offset
     * 
     * @param snapshot The snapshot to paste
     * @param offsetX X offset to apply
     * @param offsetZ Z offset to apply
     * @param includeAir Whether to include air blocks
     * @return CompletableFuture that completes when pasting is done
     */
    public static CompletableFuture<Void> pasteAsync(Snapshot snapshot, int offsetX, int offsetZ, boolean includeAir) {
        List<BlockSnapshot> blocks = new ArrayList<>();
        
        // Convert snapshot blocks with offset
        for (Map.Entry<Location, BlockData> entry : snapshot.getBlocks().entrySet()) {
            Location originalLoc = entry.getKey();
            
            // Skip air blocks if not including them
            if (!includeAir && entry.getValue().getMaterial() == Material.AIR) {
                continue;
            }
            
            // Apply offset
            Location newLoc = originalLoc.clone().add(offsetX, 0, offsetZ);
            blocks.add(new BlockSnapshot(newLoc, entry.getValue()));
        }
        
        // If we have blocks to paste and a valid world, use the enhanced async implementation
        if (!blocks.isEmpty() && blocks.get(0).getLocation() != null) {
            return setBlocksAsync(blocks.get(0).getLocation().getWorld(), blocks);
        }
        
        // Nothing to paste
        return CompletableFuture.completedFuture(null);
    }

    /**
     * Sets blocks using a list of BlockSnapshots asynchronously.
     * Blocks are processed in chunks to minimize performance impact.
     * 
     * @param world The world
     * @param blockSnapshots List of BlockSnapshots to set
     * @return CompletableFuture that completes when all blocks are set
     */
    public static CompletableFuture<Void> setBlocksAsync(World world, List<BlockSnapshot> blockSnapshots) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        
        // If no snapshots, complete immediately
        if (blockSnapshots.isEmpty()) {
            future.complete(null);
            return future;
        }
        
        Logger logger = Neptune.get().getLogger();
        
        // Sort snapshots by chunk for better performance
        Map<Long, List<BlockSnapshot>> chunkMap = new HashMap<>();
        for (BlockSnapshot snapshot : blockSnapshots) {
            Location loc = snapshot.getLocation();
            // Get chunk key (using Morton encoding for efficient storage)
            long chunkKey = (((long) loc.getBlockX() >> 4) << 32) | (((long) loc.getBlockZ() >> 4) & 0xFFFFFFFFL);
            chunkMap.computeIfAbsent(chunkKey, k -> new ArrayList<>()).add(snapshot);
        }
        
        logger.info("Organized " + blockSnapshots.size() + " blocks into " + chunkMap.size() + " chunks for processing");
        
        // Create a queue of chunks to process
        Queue<List<BlockSnapshot>> chunkQueue = new ConcurrentLinkedQueue<>(chunkMap.values());
        final int totalChunks = chunkMap.size();
        final int totalBlocks = blockSnapshots.size();
        AtomicInteger processedChunks = new AtomicInteger(0);
        AtomicInteger processedBlocks = new AtomicInteger(0);
        
        // Use a BukkitRunnable to process chunks every tick
        new BukkitRunnable() {
            @Override
            public void run() {
                // Process up to 3 chunks per tick
                int chunksThisTick = 0;
                while (!chunkQueue.isEmpty() && chunksThisTick < 3) {
                    List<BlockSnapshot> chunk = chunkQueue.poll();
                    
                    // Set all blocks in this chunk
                    for (BlockSnapshot snapshot : chunk) {
                        Block block = snapshot.getLocation().getBlock();
                        block.setBlockData(snapshot.getBlockData(), false);
                        processedBlocks.incrementAndGet();
                    }
                    
                    int processed = processedChunks.incrementAndGet();
                    chunksThisTick++;
                    
                    // Log progress for larger operations
                    if (totalChunks > 50 && processed % 50 == 0) {
                        logger.info("Arena reset progress: " + processed + "/" + totalChunks + 
                                " chunks (" + String.format("%.1f", (processed * 100.0 / totalChunks)) + "%)");
                    }
                }
                
                // If queue is empty, we're done
                if (chunkQueue.isEmpty()) {
                    // Log completion statistics
                    logger.info("Arena reset complete: " + processedBlocks.get() + " blocks in " + 
                            processedChunks.get() + " chunks processed");
                    
                    // Complete the future and cancel the task
                    future.complete(null);
                    this.cancel();
                }
            }
        }.runTaskTimer(plugin != null ? plugin : Neptune.get(), 1L, 1L);
        
        return future;
    }
    
    /**
     * Legacy method for backward compatibility with old code.
     * Sets blocks in a region with the specified material.
     * 
     * @param world The world
     * @param loc1 First corner of the region
     * @param loc2 Second corner of the region
     * @param material Material to set
     * @return CompletableFuture that completes when all blocks are set
     */
    public static CompletableFuture<Void> setBlocksAsync(World world, Location loc1, Location loc2, Material material) {
        List<BlockSnapshot> blocks = new ArrayList<>();
        
        // Ensure arguments are valid
        if (world == null || loc1 == null || loc2 == null || material == null) {
            return CompletableFuture.completedFuture(null);
        }
        
        // Get block data for the material
        BlockData blockData = Bukkit.createBlockData(material);
        
        // Get min and max coordinates
        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int minY = Math.min(loc1.getBlockY(), loc2.getBlockY());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int maxY = Math.max(loc1.getBlockY(), loc2.getBlockY());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());
        
        // Create block snapshots for all blocks in the region
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location loc = new Location(world, x, y, z);
                    blocks.add(new BlockSnapshot(loc, blockData));
                }
            }
        }
        
        // Use the new implementation to set blocks
        return setBlocksAsync(world, blocks);
    }
    
    /**
     * Legacy method for backward compatibility with old code.
     * Sets blocks in a region with the specified material.
     * 
     * @param world The world
     * @param loc1 First corner of the region
     * @param loc2 Second corner of the region
     * @param material Material to set
     */
    public static void setBlocks(World world, Location loc1, Location loc2, Material material) {
        // Call the async method but wait for it to complete
        setBlocksAsync(world, loc1, loc2, material).join();
    }
    
    /**
     * Legacy method for backward compatibility.
     * Loads chunks in a region to prepare for operations.
     * 
     * @param loc1 First corner of the region
     * @param loc2 Second corner of the region
     */
    public static void loadChunks(Location loc1, Location loc2) {
        // Ensure arguments are valid
        if (loc1 == null || loc2 == null || loc1.getWorld() == null) {
            return;
        }
        
        World world = loc1.getWorld();
        
        // Get min and max chunk coordinates
        int minChunkX = loc1.getBlockX() >> 4;
        int minChunkZ = loc1.getBlockZ() >> 4;
        int maxChunkX = loc2.getBlockX() >> 4;
        int maxChunkZ = loc2.getBlockZ() >> 4;
        
        // Ensure min <= max
        if (minChunkX > maxChunkX) {
            int temp = minChunkX;
            minChunkX = maxChunkX;
            maxChunkX = temp;
        }
        if (minChunkZ > maxChunkZ) {
            int temp = minChunkZ;
            minChunkZ = maxChunkZ;
            maxChunkZ = temp;
        }
        
        // Load chunks
        for (int x = minChunkX; x <= maxChunkX; x++) {
            for (int z = minChunkZ; z <= maxChunkZ; z++) {
                if (!world.isChunkLoaded(x, z)) {
                    world.loadChunk(x, z);
                }
            }
        }
    }
    
    /**
     * Legacy method for backward compatibility.
     * Captures blocks in a region for later restoration.
     * 
     * @param loc1 First corner of the region
     * @param loc2 Second corner of the region
     * @param includeAir Whether to include air blocks
     * @return Snapshot of the region
     */
    public static Snapshot capture(Location loc1, Location loc2, boolean includeAir) {
        Snapshot snapshot = new Snapshot();
        
        // Ensure arguments are valid
        if (loc1 == null || loc2 == null || loc1.getWorld() == null) {
            return snapshot;
        }
        
        World world = loc1.getWorld();
        
        // Get min and max coordinates
        int minX = Math.min(loc1.getBlockX(), loc2.getBlockX());
        int minY = Math.min(loc1.getBlockY(), loc2.getBlockY());
        int minZ = Math.min(loc1.getBlockZ(), loc2.getBlockZ());
        int maxX = Math.max(loc1.getBlockX(), loc2.getBlockX());
        int maxY = Math.max(loc1.getBlockY(), loc2.getBlockY());
        int maxZ = Math.max(loc1.getBlockZ(), loc2.getBlockZ());
        
        // Capture blocks
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Location loc = new Location(world, x, y, z);
                    Block block = loc.getBlock();
                    
                    // Skip air blocks if not including them
                    if (!includeAir && block.getType() == Material.AIR) {
                        continue;
                    }
                    
                    // Add block to snapshot
                    snapshot.addBlock(loc, block.getBlockData());
                }
            }
        }
        
        return snapshot;
    }
}