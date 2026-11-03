package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StockModComparisonTest {
    @TempDir Path root;

    private void fixture() throws IOException {
        Files.createDirectories(root.resolve("mods"));
        Files.createDirectories(root.resolve("stock"));
        Files.writeString(root.resolve("mods/stock.jar"), "abc");
        Files.writeString(root.resolve("mods/extra.jar"), "extra");
        Files.writeString(root.resolve("stock/manifest.json"), """
            {"version":"1", "minecraft":{"version":"1.21.1"},
             "files":[{"projectID":1,"fileID":2}]}
            """);
        Files.writeString(root.resolve("minecraftinstance.json"), """
            {"manifest":{"version":"1","minecraft":{"version":"1.21.1"}},
             "installedAddons":[{"addonID":1,"fileNameOnDisk":"stock.jar",
             "categorySection":{"path":"mods"},"installedFile":{"id":2,
             "hashes":[{"type":1,"value":"a9993e364706816aba3e25717850c26c9cd0d89d"}]}}]}
            """);
    }

    @Test void identifiesOnlyExtraJars() throws Exception {
        fixture();
        assertEquals(java.util.Set.of("extra.jar"), StockModComparison.additions(root).keySet());
    }

    @Test void rejectsChangedStock() throws Exception {
        fixture();
        Files.writeString(root.resolve("mods/stock.jar"), "changed");
        assertThrows(IOException.class, () -> StockModComparison.additions(root));
    }

    @Test void rejectsMismatchedPackVersion() throws Exception {
        fixture();
        Path manifest = root.resolve("stock/manifest.json");
        Files.writeString(manifest, Files.readString(manifest).replace("\"version\":\"1\"", "\"version\":\"2\""));
        assertThrows(IOException.class, () -> StockModComparison.additions(root));
    }

    @Test void rejectsMissingStockMod() throws Exception {
        fixture();
        Files.delete(root.resolve("mods/stock.jar"));
        assertThrows(IOException.class, () -> StockModComparison.additions(root));
    }

    @Test void rejectsStockEntryWithoutInstalledFile() throws Exception {
        fixture();
        Files.writeString(root.resolve("minecraftinstance.json"), """
            {"manifest":{"version":"1","minecraft":{"version":"1.21.1"}},
             "installedAddons":[{"addonID":1,"fileNameOnDisk":"stock.jar",
             "categorySection":{"path":"mods"}}]}
            """);
        assertThrows(IOException.class, () -> StockModComparison.additions(root));
    }

    @Test void rejectsStockFileWithoutSha1Metadata() throws Exception {
        fixture();
        Files.writeString(root.resolve("minecraftinstance.json"), """
            {"manifest":{"version":"1","minecraft":{"version":"1.21.1"}},
             "installedAddons":[{"addonID":1,"fileNameOnDisk":"stock.jar",
             "categorySection":{"path":"mods"},"installedFile":{"id":2,
             "hashes":[{"type":2,"value":"5f4dcc3b5aa765d61d8327deb882cf99"}]}}]}
            """);
        assertThrows(IOException.class, () -> StockModComparison.additions(root));
    }

    @Test void rejectsStockOverrideMismatchOrMissing() throws Exception {
        fixture();
        Files.createDirectories(root.resolve("stock/overrides/mods"));

        // Missing from the instance mods directory.
        Files.writeString(root.resolve("stock/overrides/mods/ghost.jar"), "ghost");
        assertThrows(IOException.class, () -> StockModComparison.additions(root));
        Files.delete(root.resolve("stock/overrides/mods/ghost.jar"));

        // Present but with different content than the installed file.
        Files.writeString(root.resolve("stock/overrides/mods/extra.jar"), "different");
        assertThrows(IOException.class, () -> StockModComparison.additions(root));
        Files.delete(root.resolve("stock/overrides/mods/extra.jar"));

        // Matching override is accepted and treated as stock; no additions remain.
        String content = Files.readString(root.resolve("mods/extra.jar"));
        Files.writeString(root.resolve("stock/overrides/mods/extra.jar"), content);
        assertTrue(StockModComparison.additions(root).isEmpty());
    }
}
