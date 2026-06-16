package sh.harold.fulcrum.core.content;

import java.util.List;
import java.util.Objects;

public record ContentCatalog(
        String catalogRevision,
        String objectBucket,
        List<ContentCatalogEntry> entries) {
    public ContentCatalog {
        catalogRevision = ContentNames.requireNonBlank(catalogRevision, "catalogRevision");
        objectBucket = ContentNames.requireNonBlank(objectBucket, "objectBucket");
        entries = List.copyOf(Objects.requireNonNull(entries, "entries"));
    }
}
