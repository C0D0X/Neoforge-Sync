package io.github.c0d0x.neoforgesync;

import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PublisherTest {
    private final SyncManifest manifest = new SyncManifest("1.78.1", List.of(
            new SyncManifest.ModFile("extra.jar", "a".repeat(64), 3, URI.create("https://github.com/a"))));

    @Test void detectsChangedSnapshotAndPackVersion() {
        assertTrue(Publisher.matches(manifest, "1.78.1", Map.of("extra.jar", "a".repeat(64))));
        assertFalse(Publisher.matches(manifest, "1.78.2", Map.of("extra.jar", "a".repeat(64))));
        assertFalse(Publisher.matches(manifest, "1.78.1", Map.of("extra.jar", "b".repeat(64))));
        assertFalse(Publisher.matches(manifest, "1.78.1", Map.of()));
    }
}
