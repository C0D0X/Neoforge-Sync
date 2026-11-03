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

class RecoverableApplyTest {
    @TempDir Path root;
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private record Fixture(Path mods, Path stage, Path work, SyncManifest manifest, Map<String, String> before) {}

    private Fixture fixture() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path stage = Files.createDirectory(root.resolve("stage"));
        Path work = Files.createDirectory(root.resolve("work"));
        Files.writeString(mods.resolve("old.jar"), "old");
        Files.writeString(mods.resolve("personal.jar"), "personal");
        Files.writeString(stage.resolve("new.jar"), "abc");
        Map<String, String> before = Map.of("old.jar", ModInventory.scan(mods).get("old.jar"));
        ManagedState.save(work.resolve("managed.json"), before);
        var manifest = new SyncManifest("1", List.of(new SyncManifest.ModFile("new.jar", ABC, 3, URI.create("https://github.com/a"))));
        return new Fixture(mods, stage, work, manifest, before);
    }

    @Test void successfulApplyUpdatesFilesAndState() throws Exception {
        Fixture f = fixture();
        RecoverableApply.apply(f.mods, f.stage, f.work, f.manifest);
        assertFalse(Files.exists(f.mods.resolve("old.jar")));
        assertEquals("abc", Files.readString(f.mods.resolve("new.jar")));
        assertEquals("personal", Files.readString(f.mods.resolve("personal.jar")));
        assertEquals(Map.of("new.jar", ABC), ManagedState.load(f.work.resolve("managed.json")));
        assertFalse(RecoverableApply.recover(f.mods, f.work));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"journal", "install:new.jar", "remove:old.jar", "state"})
    void restoresFilesAndStateAfterInterruption(String phase) throws Exception {
        Fixture f = fixture();
        assertThrows(IOException.class, () -> RecoverableApply.apply(f.mods, f.stage, f.work, f.manifest,
                step -> { if (step.equals(phase)) throw new IOException("interrupted"); }));
        assertTrue(RecoverableApply.recover(f.mods, f.work));
        assertEquals("old", Files.readString(f.mods.resolve("old.jar")));
        assertFalse(Files.exists(f.mods.resolve("new.jar")));
        assertEquals("personal", Files.readString(f.mods.resolve("personal.jar")));
        assertEquals(f.before, ManagedState.load(f.work.resolve("managed.json")));
        assertFalse(RecoverableApply.recover(f.mods, f.work));
    }

    @Test void refusesToOverwriteChangesMadeAfterInterruption() throws Exception {
        Fixture f = fixture();
        assertThrows(IOException.class, () -> RecoverableApply.apply(f.mods, f.stage, f.work, f.manifest,
                phase -> { if (phase.equals("install:new.jar")) throw new IOException("interrupted"); }));
        Files.writeString(f.mods.resolve("new.jar"), "edited");
        assertThrows(IOException.class, () -> RecoverableApply.recover(f.mods, f.work));
        assertEquals("edited", Files.readString(f.mods.resolve("new.jar")));
        assertTrue(Files.exists(f.work.resolve("pending.json")));
    }

    @Test void refusesConcurrentRecovery() throws Exception {
        Fixture f = fixture();
        RecoverableApply.apply(f.mods, f.stage, f.work, f.manifest, phase -> {
            if (phase.equals("journal")) assertThrows(IOException.class, () -> RecoverableApply.recover(f.mods, f.work));
        });
        assertEquals("abc", Files.readString(f.mods.resolve("new.jar")));
    }
}
