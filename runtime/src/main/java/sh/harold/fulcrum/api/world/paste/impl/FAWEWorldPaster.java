package sh.harold.fulcrum.api.world.paste.impl;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.function.operation.Operation;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.world.World;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import sh.harold.fulcrum.api.world.paste.*;
import sh.harold.fulcrum.runtime.threading.PaperRuntime;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * FAWE implementation of WorldPaster.
 */
public class FAWEWorldPaster implements WorldPaster {
    
    private final Plugin plugin;
    private final Logger logger;
    private final Map<String, PasteOperation> activeOperations = new ConcurrentHashMap<>();
    private final PaperRuntime runtime;
    
    @Deprecated
    public FAWEWorldPaster(Plugin plugin) {
        this(plugin, null);
    }

    public FAWEWorldPaster(Plugin plugin, PaperRuntime runtime) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.runtime = runtime;
    }
    
    @Override
    public CompletableFuture<PasteResult> pasteSchematic(File schematic, Location origin) {
        return pasteSchematic(schematic, origin, PasteOptions.defaults());
    }
    
    @Override
    public CompletableFuture<PasteResult> pasteSchematic(File schematic, Location origin, PasteOptions options) {
        return pasteWithTransform(schematic, origin, Transform.identity(), options);
    }
    
    @Override
    public CompletableFuture<PasteResult> pasteRegion(Region source, Location destination) {
        return pasteRegion(source, destination, PasteOptions.defaults());
    }
    
    @Override
    public CompletableFuture<PasteResult> pasteRegion(Region source, Location destination, PasteOptions options) {
        String operationId = generateOperationId();
        Instant startTime = Instant.now();
        
        return callSync("paste region " + operationId, () -> {
            try {
                World world = BukkitAdapter.adapt(source.getWorld());
                
                // Create edit session
                EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                        .world(world)
                        .fastMode(options.isFastMode())
                        .build();
                
                // Create region for copying
                com.sk89q.worldedit.regions.CuboidRegion region = new com.sk89q.worldedit.regions.CuboidRegion(
                        world,
                        BlockVector3.at(source.getMin().getX(), source.getMin().getY(), source.getMin().getZ()),
                        BlockVector3.at(source.getMax().getX(), source.getMax().getY(), source.getMax().getZ())
                );
                
                // Copy the region
                Clipboard clipboard = editSession.lazyCopy(region);
                
                // Paste at destination
                BlockVector3 to = BlockVector3.at(destination.getX(), destination.getY(), destination.getZ());
                Operation operation = new ClipboardHolder(clipboard)
                        .createPaste(editSession)
                        .to(to)
                        .ignoreAirBlocks(options.isIgnoreAirBlocks())
                        .copyEntities(options.isCopyEntities())
                        .copyBiomes(options.isCopyBiomes())
                        .build();
                
                Operations.complete(operation);
                editSession.close();
                
                int blocksAffected = editSession.getBlockChangeCount();
                Region affectedRegion = calculateAffectedRegion(destination, clipboard);
                
                PasteResult result = PasteResult.success(
                        operationId,
                        blocksAffected,
                        0, // Entity count would need additional tracking
                        startTime,
                        Instant.now(),
                        affectedRegion
                );
                
                return result;
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to paste region", e);
                return PasteResult.failure(operationId, e.getMessage(), startTime);
            }
        });
    }
    
    @Override
    public CompletableFuture<PasteResult> pasteWithTransform(File schematic, Location origin, Transform transform) {
        return pasteWithTransform(schematic, origin, transform, PasteOptions.defaults());
    }
    
    @Override
    public CompletableFuture<PasteResult> pasteWithTransform(File schematic, Location origin, 
                                                            Transform transform, PasteOptions options) {
        if (!validateSchematic(schematic)) {
            return CompletableFuture.completedFuture(
                    PasteResult.failure(generateOperationId(), "Invalid schematic file: " + schematic.getName(), 
                            Instant.now())
            );
        }
        
        String operationId = generateOperationId();
        Instant startTime = Instant.now();
        
        CompletableFuture<PasteResult> future = callSync("paste schematic " + operationId, () -> {
            try {
                // Load the schematic
                ClipboardFormat format = ClipboardFormats.findByFile(schematic);
                if (format == null) {
                    throw new IOException("Unknown schematic format");
                }
                
                Clipboard clipboard;
                try (FileInputStream fis = new FileInputStream(schematic);
                     ClipboardReader reader = format.getReader(fis)) {
                    clipboard = reader.read();
                }
                
                // Prepare the world and location
                World world = BukkitAdapter.adapt(origin.getWorld());
                BlockVector3 to = BlockVector3.at(origin.getX(), origin.getY(), origin.getZ());
                
                // Create edit session
                EditSession editSession = WorldEdit.getInstance().newEditSessionBuilder()
                        .world(world)
                        .fastMode(options.isFastMode())
                        .build();
                
                // Apply transformations
                AffineTransform affineTransform = new AffineTransform();
                
                // Apply rotation
                if (transform.getRotationY() != 0) {
                    affineTransform = affineTransform.rotateY(transform.getRotationY());
                }
                
                // Apply flips
                if (transform.isFlipX()) {
                    affineTransform = affineTransform.scale(-1, 1, 1);
                }
                if (transform.isFlipZ()) {
                    affineTransform = affineTransform.scale(1, 1, -1);
                }
                
                // Apply scaling if not default
                if (transform.getScaleX() != 1.0 || transform.getScaleY() != 1.0 || transform.getScaleZ() != 1.0) {
                    affineTransform = affineTransform.scale(
                            transform.getScaleX(),
                            transform.getScaleY(),
                            transform.getScaleZ()
                    );
                }
                
                // Create clipboard holder and apply transform
                ClipboardHolder holder = new ClipboardHolder(clipboard);
                if (!affineTransform.isIdentity()) {
                    holder.setTransform(affineTransform);
                }
                
                // Create paste operation
                Operation pasteOperation = holder
                        .createPaste(editSession)
                        .to(to)
                        .ignoreAirBlocks(options.isIgnoreAirBlocks())
                        .copyEntities(options.isCopyEntities())
                        .copyBiomes(options.isCopyBiomes())
                        .build();
                
                // Execute the paste
                if (options.isFastMode() || options.getTicksPerOperation() == 1) {
                    // Execute immediately
                    Operations.complete(pasteOperation);
                } else {
                    // Execute with delay between operations
                    executeWithDelay(pasteOperation, editSession, options.getTicksPerOperation(), null);
                }
                
                editSession.close();
                
                // Calculate results
                int blocksAffected = editSession.getBlockChangeCount();
                Region affectedRegion = calculateAffectedRegion(origin, clipboard);
                
                PasteResult result = PasteResult.success(
                        operationId,
                        blocksAffected,
                        0, // Entity count would need additional tracking
                        startTime,
                        Instant.now(),
                        affectedRegion
                );
                
                return result;
                
            } catch (Exception e) {
                logger.log(Level.SEVERE, "Failed to paste schematic: " + schematic.getName(), e);
                return PasteResult.failure(operationId, e.getMessage(), startTime);
            }
        });
        if (options.isTrackProgress()) {
            activeOperations.put(operationId, new PasteOperation(operationId, future));
        }
        future.whenComplete((ignored, throwable) -> {
            activeOperations.remove(operationId);
        });
        
        return future;
    }
    
    @Override
    public boolean validateSchematic(File schematic) {
        if (!schematic.exists() || !schematic.isFile()) {
            return false;
        }
        
        try {
            ClipboardFormat format = ClipboardFormats.findByFile(schematic);
            return format != null;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to validate schematic: " + schematic.getName(), e);
            return false;
        }
    }
    
    @Override
    public int getProgress(String operationId) {
        PasteOperation operation = activeOperations.get(operationId);
        return operation != null ? operation.getProgress() : -1;
    }
    
    @Override
    public boolean cancelOperation(String operationId) {
        PasteOperation operation = activeOperations.remove(operationId);
        if (operation != null) {
            operation.cancel();
            return true;
        }
        return false;
    }
    
    private String generateOperationId() {
        return UUID.randomUUID().toString();
    }

    private <T> CompletableFuture<T> callSync(String operation, java.util.function.Supplier<T> supplier) {
        if (runtime != null) {
            return runtime.callSync(operation, supplier);
        }
        if (!Bukkit.isPrimaryThread()) {
            return CompletableFuture.failedFuture(new IllegalStateException(operation + " requires the Paper primary thread"));
        }
        try {
            return CompletableFuture.completedFuture(supplier.get());
        } catch (Throwable throwable) {
            return CompletableFuture.failedFuture(throwable);
        }
    }
    
    private Region calculateAffectedRegion(Location origin, Clipboard clipboard) {
        BlockVector3 min = clipboard.getMinimumPoint();
        BlockVector3 max = clipboard.getMaximumPoint();
        BlockVector3 size = max.subtract(min).add(1, 1, 1);
        
        org.bukkit.util.Vector minCorner = new org.bukkit.util.Vector(
                origin.getX(),
                origin.getY(),
                origin.getZ()
        );
        
        org.bukkit.util.Vector maxCorner = new org.bukkit.util.Vector(
                origin.getX() + size.x(),
                origin.getY() + size.y(),
                origin.getZ() + size.z()
        );
        
        return new Region(origin.getWorld(), minCorner, maxCorner);
    }
    
    private void executeWithDelay(Operation operation, EditSession editSession, int ticksDelay, 
                                 PasteOperation trackedOperation) {
        // This would need to be implemented with proper chunking and delay
        // For now, just complete immediately
        try {
            Operations.complete(operation);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to complete paste operation", e);
        }
    }
    
    /**
     * Internal class to track paste operations.
     */
    private static class PasteOperation {
        private final String id;
        private final CompletableFuture<PasteResult> future;
        private volatile int progress = 0;
        private volatile boolean cancelled = false;
        
        PasteOperation(String id, CompletableFuture<PasteResult> future) {
            this.id = id;
            this.future = future;
        }
        
        int getProgress() {
            return progress;
        }
        
        void setProgress(int progress) {
            this.progress = Math.min(100, Math.max(0, progress));
        }
        
        void cancel() {
            cancelled = true;
            future.cancel(true);
        }
        
        boolean isCancelled() {
            return cancelled;
        }
    }
}
