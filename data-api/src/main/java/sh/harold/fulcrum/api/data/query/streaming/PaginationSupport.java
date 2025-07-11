package sh.harold.fulcrum.api.data.query.streaming;

import sh.harold.fulcrum.api.data.query.CrossSchemaResult;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Provides pagination utilities for cross-schema queries.
 * 
 * <p>Supports both offset-based and cursor-based pagination strategies,
 * allowing efficient navigation through large result sets.</p>
 * 
 * <p>Features:</p>
 * <ul>
 *   <li>Offset/limit pagination for random access</li>
 *   <li>Cursor-based pagination for efficient sequential access</li>
 *   <li>Page metadata including total count and navigation info</li>
 *   <li>Async-first design for non-blocking operations</li>
 * </ul>
 * 
 * @author Harold
 * @since 1.0
 */
public class PaginationSupport {
    
    /**
     * Represents a page of results with metadata.
     * 
     * @param <T> The type of items in the page
     */
    public static class Page<T> {
        private final List<T> content;
        private final int pageNumber;
        private final int pageSize;
        private final long totalElements;
        private final int totalPages;
        private final boolean hasNext;
        private final boolean hasPrevious;
        private final String nextCursor;
        private final String previousCursor;
        
        /**
         * Creates a new Page with offset-based pagination info.
         */
        public Page(List<T> content, int pageNumber, int pageSize, long totalElements) {
            this.content = Collections.unmodifiableList(content);
            this.pageNumber = pageNumber;
            this.pageSize = pageSize;
            this.totalElements = totalElements;
            this.totalPages = (int) Math.ceil((double) totalElements / pageSize);
            this.hasNext = pageNumber < totalPages - 1;
            this.hasPrevious = pageNumber > 0;
            this.nextCursor = null;
            this.previousCursor = null;
        }
        
        /**
         * Creates a new Page with cursor-based pagination info.
         */
        public Page(List<T> content, int pageSize, long totalElements, 
                   String nextCursor, String previousCursor) {
            this.content = Collections.unmodifiableList(content);
            this.pageNumber = -1; // Not applicable for cursor pagination
            this.pageSize = pageSize;
            this.totalElements = totalElements;
            this.totalPages = -1; // Not applicable for cursor pagination
            this.hasNext = nextCursor != null;
            this.hasPrevious = previousCursor != null;
            this.nextCursor = nextCursor;
            this.previousCursor = previousCursor;
        }
        
        /**
         * Gets the content of this page.
         */
        public List<T> getContent() {
            return content;
        }
        
        /**
         * Gets the page number (0-based).
         * Returns -1 for cursor-based pagination.
         */
        public int getPageNumber() {
            return pageNumber;
        }
        
        /**
         * Gets the size of this page.
         */
        public int getPageSize() {
            return pageSize;
        }
        
        /**
         * Gets the total number of elements across all pages.
         */
        public long getTotalElements() {
            return totalElements;
        }
        
        /**
         * Gets the total number of pages.
         * Returns -1 for cursor-based pagination.
         */
        public int getTotalPages() {
            return totalPages;
        }
        
        /**
         * Checks if there is a next page.
         */
        public boolean hasNext() {
            return hasNext;
        }
        
        /**
         * Checks if there is a previous page.
         */
        public boolean hasPrevious() {
            return hasPrevious;
        }
        
        /**
         * Gets the cursor for the next page (cursor-based pagination).
         */
        public Optional<String> getNextCursor() {
            return Optional.ofNullable(nextCursor);
        }
        
        /**
         * Gets the cursor for the previous page (cursor-based pagination).
         */
        public Optional<String> getPreviousCursor() {
            return Optional.ofNullable(previousCursor);
        }
        
        /**
         * Checks if this page is empty.
         */
        public boolean isEmpty() {
            return content.isEmpty();
        }
        
        /**
         * Checks if this is the first page.
         */
        public boolean isFirst() {
            return pageNumber == 0 || !hasPrevious;
        }
        
        /**
         * Checks if this is the last page.
         */
        public boolean isLast() {
            return !hasNext;
        }
        
        /**
         * Maps the content of this page to a different type.
         */
        public <U> Page<U> map(Function<T, U> mapper) {
            List<U> mappedContent = new ArrayList<>();
            for (T item : content) {
                mappedContent.add(mapper.apply(item));
            }
            
            if (pageNumber >= 0) {
                // Offset-based
                return new Page<>(mappedContent, pageNumber, pageSize, totalElements);
            } else {
                // Cursor-based
                return new Page<>(mappedContent, pageSize, totalElements, nextCursor, previousCursor);
            }
        }
    }
    
    /**
     * Configuration for pagination behavior.
     */
    public static class PaginationConfig {
        private final int maxPageSize;
        private final int defaultPageSize;
        private final boolean countTotal;
        private final CursorStrategy cursorStrategy;
        
        /**
         * Default configuration with sensible defaults.
         */
        public PaginationConfig() {
            this(1000, 50, true, CursorStrategy.UUID_BASED);
        }
        
        /**
         * Custom configuration.
         */
        public PaginationConfig(int maxPageSize, int defaultPageSize, 
                               boolean countTotal, CursorStrategy cursorStrategy) {
            this.maxPageSize = maxPageSize;
            this.defaultPageSize = defaultPageSize;
            this.countTotal = countTotal;
            this.cursorStrategy = cursorStrategy;
        }
        
        public int getMaxPageSize() {
            return maxPageSize;
        }
        
        public int getDefaultPageSize() {
            return defaultPageSize;
        }
        
        public boolean isCountTotal() {
            return countTotal;
        }
        
        public CursorStrategy getCursorStrategy() {
            return cursorStrategy;
        }
    }
    
    /**
     * Strategies for cursor generation.
     */
    public enum CursorStrategy {
        /**
         * Use UUID as cursor (most efficient for UUID-based queries).
         */
        UUID_BASED,
        
        /**
         * Use timestamp as cursor (good for time-ordered data).
         */
        TIMESTAMP_BASED,
        
        /**
         * Use a combination of fields as cursor.
         */
        COMPOSITE,
        
        /**
         * Use opaque tokens (requires external storage).
         */
        OPAQUE_TOKEN
    }
    
    /**
     * Interface for cursor encoding/decoding.
     */
    public interface CursorCodec {
        /**
         * Encodes result data into a cursor string.
         */
        String encode(CrossSchemaResult result);
        
        /**
         * Decodes a cursor string back to usable data.
         */
        Map<String, Object> decode(String cursor);
    }
    
    /**
     * UUID-based cursor codec implementation.
     */
    public static class UuidCursorCodec implements CursorCodec {
        @Override
        public String encode(CrossSchemaResult result) {
            // Simple base64 encoding of UUID
            return Base64.getEncoder().encodeToString(
                result.getPlayerUuid().toString().getBytes()
            );
        }
        
        @Override
        public Map<String, Object> decode(String cursor) {
            try {
                String uuidStr = new String(Base64.getDecoder().decode(cursor));
                Map<String, Object> decoded = new HashMap<>();
                decoded.put("uuid", UUID.fromString(uuidStr));
                return decoded;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid cursor: " + cursor, e);
            }
        }
    }
    
    /**
     * Composite cursor codec that includes multiple fields.
     */
    public static class CompositeCursorCodec implements CursorCodec {
        private final List<String> fields;
        
        public CompositeCursorCodec(List<String> fields) {
            this.fields = new ArrayList<>(fields);
        }
        
        @Override
        public String encode(CrossSchemaResult result) {
            Map<String, Object> cursorData = new HashMap<>();
            cursorData.put("uuid", result.getPlayerUuid().toString());
            
            for (String field : fields) {
                Object value = result.getField(field);
                if (value != null) {
                    cursorData.put(field, value.toString());
                }
            }
            
            // Simple JSON-like encoding
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, Object> entry : cursorData.entrySet()) {
                if (sb.length() > 0) sb.append(";");
                sb.append(entry.getKey()).append("=").append(entry.getValue());
            }
            
            return Base64.getEncoder().encodeToString(sb.toString().getBytes());
        }
        
        @Override
        public Map<String, Object> decode(String cursor) {
            try {
                String decoded = new String(Base64.getDecoder().decode(cursor));
                Map<String, Object> result = new HashMap<>();
                
                for (String pair : decoded.split(";")) {
                    String[] parts = pair.split("=", 2);
                    if (parts.length == 2) {
                        if ("uuid".equals(parts[0])) {
                            result.put("uuid", UUID.fromString(parts[1]));
                        } else {
                            result.put(parts[0], parts[1]);
                        }
                    }
                }
                
                return result;
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid cursor: " + cursor, e);
            }
        }
    }
    
    /**
     * Request for offset-based pagination.
     */
    public static class OffsetPageRequest {
        private final int offset;
        private final int limit;
        
        public OffsetPageRequest(int offset, int limit) {
            if (offset < 0) {
                throw new IllegalArgumentException("Offset cannot be negative");
            }
            if (limit <= 0) {
                throw new IllegalArgumentException("Limit must be positive");
            }
            this.offset = offset;
            this.limit = limit;
        }
        
        public int getOffset() {
            return offset;
        }
        
        public int getLimit() {
            return limit;
        }
        
        /**
         * Creates a page request from page number and size.
         */
        public static OffsetPageRequest of(int pageNumber, int pageSize) {
            if (pageNumber < 0) {
                throw new IllegalArgumentException("Page number cannot be negative");
            }
            return new OffsetPageRequest(pageNumber * pageSize, pageSize);
        }
    }
    
    /**
     * Request for cursor-based pagination.
     */
    public static class CursorPageRequest {
        private final String cursor;
        private final int limit;
        private final Direction direction;
        
        public enum Direction {
            FORWARD,
            BACKWARD
        }
        
        public CursorPageRequest(String cursor, int limit, Direction direction) {
            this.cursor = cursor;
            this.limit = limit;
            this.direction = direction;
        }
        
        public Optional<String> getCursor() {
            return Optional.ofNullable(cursor);
        }
        
        public int getLimit() {
            return limit;
        }
        
        public Direction getDirection() {
            return direction;
        }
        
        /**
         * Creates a request for the next page.
         */
        public static CursorPageRequest next(String cursor, int limit) {
            return new CursorPageRequest(cursor, limit, Direction.FORWARD);
        }
        
        /**
         * Creates a request for the previous page.
         */
        public static CursorPageRequest previous(String cursor, int limit) {
            return new CursorPageRequest(cursor, limit, Direction.BACKWARD);
        }
        
        /**
         * Creates a request for the first page.
         */
        public static CursorPageRequest first(int limit) {
            return new CursorPageRequest(null, limit, Direction.FORWARD);
        }
    }
    
    /**
     * Utility method to create a page from a list of results.
     */
    public static <T> Page<T> createPage(List<T> allResults, OffsetPageRequest request, long totalCount) {
        int fromIndex = Math.min(request.getOffset(), allResults.size());
        int toIndex = Math.min(request.getOffset() + request.getLimit(), allResults.size());
        
        List<T> pageContent = allResults.subList(fromIndex, toIndex);
        int pageNumber = request.getOffset() / request.getLimit();
        
        return new Page<>(pageContent, pageNumber, request.getLimit(), totalCount);
    }
    
    /**
     * Async utility to count total results.
     */
    public static CompletableFuture<Long> countAsync(
            Function<Void, CompletableFuture<Long>> countFunction) {
        return countFunction.apply(null);
    }
}