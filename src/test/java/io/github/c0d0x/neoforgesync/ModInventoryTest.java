package io.github.c0d0x.neoforgesync;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

public final class ModInventoryTest {
    @Test
    void inventoryTracksChanges() throws Exception {
        Path directory = Files.createTempDirectory("neoforge-sync-inventory-");
        try {
            Files.writeString(directory.resolve("test.jar"), "abc");
            Files.writeString(directory.resolve("ignored.txt"), "ignored");
            var inventory = ModInventory.scan(directory);
            if (inventory.size() != 1 || !"ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad".equals(inventory.get("test.jar"))) {
                throw new AssertionError("Incorrect inventory: " + inventory);
            }
            Files.writeString(directory.resolve("test.jar"), "changed");
            if (inventory.equals(ModInventory.scan(directory))) throw new AssertionError("Change not detected");
            Files.delete(directory.resolve("test.jar"));
            if (!ModInventory.scan(directory).isEmpty()) throw new AssertionError("Removal not detected");
            try {
                ModInventory.scan(directory.resolve("missing"));
                throw new AssertionError("Missing directory accepted");
            } catch (java.io.IOException expected) {
                // Expected.
            }
        } finally {
            try (var files = Files.list(directory)) {
                for (Path file : files.toList()) Files.delete(file);
            }
            Files.delete(directory);
        }
        System.out.println("ModInventory checks passed");
    }
}
