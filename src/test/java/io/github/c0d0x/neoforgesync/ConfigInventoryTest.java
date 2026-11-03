package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ConfigInventoryTest {
    @TempDir Path root;

    @Test void reportsAddedChangedAndRemovedFiles() throws Exception {
        Files.createDirectories(root.resolve("nested"));
        Files.writeString(root.resolve("nested/changed.toml"), "before");
        Files.writeString(root.resolve("removed.toml"), "remove");
        Files.writeString(root.resolve("same.toml"), "same");
        var baseline = ConfigInventory.scan(root);
        Files.writeString(root.resolve("nested/changed.toml"), "after");
        Files.delete(root.resolve("removed.toml"));
        Files.writeString(root.resolve("new.toml"), "new");
        var changes = ConfigInventory.compare(baseline, ConfigInventory.scan(root));
        assertEquals(Set.of("new.toml"), changes.added());
        assertEquals(Set.of("nested/changed.toml"), changes.changed());
        assertEquals(Set.of("removed.toml"), changes.removed());
    }

    @Test void rejectsMissingDirectory() {
        assertThrows(IOException.class, () -> ConfigInventory.scan(root.resolve("missing")));
    }
}
