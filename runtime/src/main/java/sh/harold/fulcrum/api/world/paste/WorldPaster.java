package sh.harold.fulcrum.api.world.paste;

import org.bukkit.Location;

import java.io.File;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for pasting schematics and regions in the world.
 * Provides async operations for world manipulation without server lag.
 */
public interface WorldPaster {

    /**
     * Paste a schematic file at the specified origin location.
     *
     * @param schematic The schematic file to paste
     * @param origin    The origin location for pasting
     * @return A future containing the paste result
     */
    CompletableFuture<PasteResult> pasteSchematic(File schematic, Location origin);

    /**
     * Paste a schematic file with specific options.
     *
     * @param schematic The schematic file to paste
     * @param origin    The origin location for pasting
     * @param options   The paste options
     * @return A future containing the paste result
     */
    CompletableFuture<PasteResult> pasteSchematic(File schematic, Location origin, PasteOptions options);

    /**
     * Copy and paste a region from one location to another.
     *
     * @param source      The source region to copy
     * @param destination The destination location
     * @return A future containing the paste result
     */
    CompletableFuture<PasteResult> pasteRegion(Region source, Location destination);

    /**
     * Copy and paste a region with specific options.
     *
     * @param source      The source region to copy
     * @param destination The destination location
     * @param options     The paste options
     * @return A future containing the paste result
     */
    CompletableFuture<PasteResult> pasteRegion(Region source, Location destination, PasteOptions options);

    /**
     * Paste a schematic with transformation (rotation, flip, etc).
     *
     * @param schematic The schematic file to paste
     * @param origin    The origin location for pasting
     * @param transform The transformation to apply
     * @return A future containing the paste result
     */
    CompletableFuture<PasteResult> pasteWithTransform(File schematic, Location origin, Transform transform);

    /**
     * Paste a schematic with transformation and options.
     *
     * @param schematic The schematic file to paste
     * @param origin    The origin location for pasting
     * @param transform The transformation to apply
     * @param options   The paste options
     * @return A future containing the paste result
     */
    CompletableFuture<PasteResult> pasteWithTransform(File schematic, Location origin, Transform transform, PasteOptions options);

    /**
     * Load a schematic file for validation without pasting.
     *
     * @param schematic The schematic file to validate
     * @return true if the schematic is valid and can be pasted
     */
    boolean validateSchematic(File schematic);

    /**
     * Get progress of an ongoing paste operation.
     *
     * @param operationId The operation ID from PasteResult
     * @return Progress percentage (0-100), or -1 if operation not found
     */
    int getProgress(String operationId);

    /**
     * Cancel an ongoing paste operation.
     *
     * @param operationId The operation ID from PasteResult
     * @return true if the operation was cancelled
     */
    boolean cancelOperation(String operationId);
}