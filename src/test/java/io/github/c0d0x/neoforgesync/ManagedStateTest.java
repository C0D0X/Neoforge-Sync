package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ManagedStateTest {
    @TempDir Path root;

    @Test void roundTripsAtomically() throws Exception {
        Path file = root.resolve("state.json");
        assertEquals(Map.of(), ManagedState.load(file));
        var files = Map.of("extra.jar", "a".repeat(64));
        ManagedState.save(file, files);
        assertEquals(files, ManagedState.load(file));
        ManagedState.save(file, Map.of());
        assertEquals(Map.of(), ManagedState.load(file));
    }

    @Test void refusesMalformedOrUnsafeState() throws Exception {
        Path file = root.resolve("state.json");
        assertThrows(IOException.class, () -> ManagedState.save(file, Map.of("../evil.jar", "a".repeat(64))));
        Files.writeString(file, "{\"schema\":1,\"mods\":{\"Mod.jar\":\"" + "a".repeat(64)
                + "\",\"mod.jar\":\"" + "a".repeat(64) + "\"}}");
        assertThrows(IOException.class, () -> ManagedState.load(file));
        Files.writeString(file, "{broken");
        assertThrows(IOException.class, () -> ManagedState.load(file));
    }
}
