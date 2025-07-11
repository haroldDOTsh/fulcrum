package sh.harold.fulcrum.api.data.query.streaming;

import sh.harold.fulcrum.api.data.query.CrossSchemaResult;

import java.util.Iterator;
import java.util.UUID;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Async streaming wrapper for processing large result sets efficiently.
 * Provides backpressure handling and memory-efficient streaming of query results.
 * 
 * <p>This class implements a reactive-style stream that processes results as they
 * arrive from the backend, preventing memory overflow for large datasets.</p>
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Lazy evaluation of results</li>
 *   <li>Configurable buffer sizes for backpressure</li>
 *   <li>Automatic cleanup of resources</li>
 *   <li>Cancellation support</li>
 *   <li>Timeout handling</li>
 * </ul>
 * 
 * @author Harold
 * @since 1.0
 */
public class AsyncResultStream implements AutoCloseable {
    
    private final BlockingQueue<CrossSchemaResult> buffer;
    private final ExecutorService executorService;
    private final AtomicBoolean isClosed;
    private final AtomicBoolean isCancelled;
    private final AtomicInteger activeProducers;
    private final CompletableFuture<Void> streamCompletion;
    private final BackpressureHandler backpressureHandler;
    private final long timeoutMillis;
    
    // Sentinel object to signal end of stream
    private static final CrossSchemaResult END_OF_STREAM = new CrossSchemaResult(new UUID(0L, 0L));
    
    /**
     * Creates a new AsyncResultStream with default settings.
     * 
     * @param bufferSize The size of the internal buffer for backpressure handling
     */
    public AsyncResultStream(int bufferSize) {
        this(bufferSize, ForkJoinPool.commonPool(), new BackpressureHandler(), 0);
    }
    
    /**
     * Creates a new AsyncResultStream with custom settings.
     * 
     * @param bufferSize The size of the internal buffer
     * @param executorService The executor service for async processing
     * @param backpressureHandler Handler for managing memory pressure
     * @param timeoutMillis Timeout in milliseconds (0 for no timeout)
     */
    public AsyncResultStream(int bufferSize, ExecutorService executorService,
                           BackpressureHandler backpressureHandler, long timeoutMillis) {
        if (bufferSize <= 0) {
            throw new IllegalArgumentException("Buffer size must be positive");
        }
        
        this.buffer = new LinkedBlockingQueue<>(bufferSize);
        this.executorService = executorService;
        this.backpressureHandler = backpressureHandler;
        this.timeoutMillis = timeoutMillis;
        this.isClosed = new AtomicBoolean(false);
        this.isCancelled = new AtomicBoolean(false);
        this.activeProducers = new AtomicInteger(0);
        this.streamCompletion = new CompletableFuture<>();
    }
    
    /**
     * Adds a result to the stream.
     * Blocks if the buffer is full (backpressure).
     * 
     * @param result The result to add
     * @throws InterruptedException if interrupted while waiting
     * @throws IllegalStateException if the stream is closed or cancelled
     */
    public void add(CrossSchemaResult result) throws InterruptedException {
        if (isClosed.get() || isCancelled.get()) {
            throw new IllegalStateException("Cannot add to closed or cancelled stream");
        }
        
        while (!buffer.offer(result, 100, TimeUnit.MILLISECONDS)) {
            if (isCancelled.get()) {
                throw new CancellationException("Stream was cancelled");
            }
            
            // Apply backpressure strategy
            backpressureHandler.handleBackpressure(buffer.size(), buffer.remainingCapacity());
        }
    }
    
    /**
     * Adds a result asynchronously.
     * 
     * @param result The result to add
     * @return A CompletableFuture that completes when the result is added
     */
    public CompletableFuture<Void> addAsync(CrossSchemaResult result) {
        return CompletableFuture.runAsync(() -> {
            try {
                add(result);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            }
        }, executorService);
    }
    
    /**
     * Marks a producer as active.
     * Must be called before a producer starts adding results.
     */
    public void registerProducer() {
        activeProducers.incrementAndGet();
    }
    
    /**
     * Marks a producer as completed.
     * When all producers are done, the stream is automatically closed.
     */
    public void producerCompleted() {
        if (activeProducers.decrementAndGet() == 0) {
            // All producers done, signal end of stream
            try {
                buffer.put(END_OF_STREAM);
                streamCompletion.complete(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                streamCompletion.completeExceptionally(e);
            }
        }
    }
    
    /**
     * Converts this async stream to a Java Stream for processing.
     * 
     * @return A Stream of CrossSchemaResult
     */
    public Stream<CrossSchemaResult> stream() {
        return StreamSupport.stream(
            Spliterators.spliteratorUnknownSize(new StreamIterator(), Spliterator.ORDERED),
            false
        ).onClose(this::close);
    }
    
    /**
     * Processes each result asynchronously as it arrives.
     * 
     * @param action The action to perform on each result
     * @return A CompletableFuture that completes when all results are processed
     */
    public CompletableFuture<Void> forEachAsync(Consumer<CrossSchemaResult> action) {
        return CompletableFuture.runAsync(() -> {
            try {
                CrossSchemaResult result;
                while ((result = take()) != null) {
                    action.accept(result);
                }
            } catch (Exception e) {
                throw new CompletionException(e);
            }
        }, executorService);
    }
    
    /**
     * Takes the next result from the stream.
     * Blocks until a result is available or the stream ends.
     * 
     * @return The next result, or null if the stream has ended
     * @throws InterruptedException if interrupted while waiting
     */
    public CrossSchemaResult take() throws InterruptedException {
        if (timeoutMillis > 0) {
            CrossSchemaResult result = buffer.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            if (result == null) {
                throw new TimeoutException("Stream timed out after " + timeoutMillis + "ms");
            }
            return result == END_OF_STREAM ? null : result;
        } else {
            CrossSchemaResult result = buffer.take();
            return result == END_OF_STREAM ? null : result;
        }
    }
    
    /**
     * Cancels the stream, preventing further processing.
     */
    public void cancel() {
        isCancelled.set(true);
        streamCompletion.cancel(true);
        close();
    }
    
    /**
     * Checks if the stream has been cancelled.
     * 
     * @return true if cancelled, false otherwise
     */
    public boolean isCancelled() {
        return isCancelled.get();
    }
    
    /**
     * Gets a CompletableFuture that completes when the stream ends.
     * 
     * @return A CompletableFuture for stream completion
     */
    public CompletableFuture<Void> completion() {
        return streamCompletion.copy();
    }
    
    @Override
    public void close() {
        if (isClosed.compareAndSet(false, true)) {
            // Clear the buffer
            buffer.clear();
            
            // Complete the stream if not already done
            streamCompletion.complete(null);
        }
    }
    
    /**
     * Iterator implementation for converting to Java Stream.
     */
    private class StreamIterator implements Iterator<CrossSchemaResult> {
        private CrossSchemaResult next;
        
        @Override
        public boolean hasNext() {
            if (next != null) {
                return true;
            }
            
            try {
                next = take();
                return next != null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted while reading stream", e);
            }
        }
        
        @Override
        public CrossSchemaResult next() {
            if (!hasNext()) {
                throw new IllegalStateException("No more elements");
            }
            
            CrossSchemaResult result = next;
            next = null;
            return result;
        }
    }
    
    /**
     * Custom timeout exception for stream operations.
     */
    public static class TimeoutException extends RuntimeException {
        public TimeoutException(String message) {
            super(message);
        }
    }
}