package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ApplyTransactionTest {
    @TempDir Path root;
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private SyncManifest manifest() {
        return new SyncManifest("1", List.of(new SyncManifest.ModFile("new.jar", ABC, 3, URI.create("https://github.com/a"))));
    }

    private void directories() throws IOException {
        Files.createDirectory(root.resolve("mods"));
        Files.createDirectory(root.resolve("staged"));
        Files.createDirectory(root.resolve("backups"));
    }

    @Test void replacesOnlyManagedFilesAndRetainsBackup() throws Exception {
        directories();
        Files.writeString(root.resolve("mods/old.jar"), "old");
        Files.writeString(root.resolve("mods/personal.jar"), "personal");
        Files.writeString(root.resolve("staged/new.jar"), "abc");
        String old = ModInventory.scan(root.resolve("mods")).get("old.jar");
        ApplyTransaction.apply(root.resolve("mods"), root.resolve("staged"), root.resolve("backups"), Map.of("old.jar", old), manifest());
        assertFalse(Files.exists(root.resolve("mods/old.jar")));
        assertEquals("abc", Files.readString(root.resolve("mods/new.jar")));
        assertEquals("personal", Files.readString(root.resolve("mods/personal.jar")));
        try (var dirs = Files.list(root.resolve("backups"))) {
            assertEquals("old", Files.readString(dirs.findFirst().orElseThrow().resolve("old.jar")));
        }
    }

    @Test void corruptStagingDoesNotChangeLiveMods() throws Exception {
        directories();
        Files.writeString(root.resolve("mods/old.jar"), "old");
        Files.writeString(root.resolve("staged/new.jar"), "bad");
        String old = ModInventory.scan(root.resolve("mods")).get("old.jar");
        assertThrows(IOException.class, () -> ApplyTransaction.apply(root.resolve("mods"), root.resolve("staged"), root.resolve("backups"), Map.of("old.jar", old), manifest()));
        assertEquals("old", Files.readString(root.resolve("mods/old.jar")));
        assertFalse(Files.exists(root.resolve("mods/new.jar")));
    }
}
