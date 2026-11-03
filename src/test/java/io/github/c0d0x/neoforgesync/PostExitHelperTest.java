package io.github.c0d0x.neoforgesync;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PostExitHelperTest {
    @TempDir Path root;
    public static class Sleeper { public static void main(String[] args) throws Exception { Thread.sleep(30000); } }

    @Test void waitsForSpecifiedProcessBeforeApplying() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path work = Files.createDirectory(root.resolve(".neoforgesync"));
        Path stage = Files.createDirectory(work.resolve("stage-test"));
        Files.writeString(root.resolve("minecraftinstance.json"), "{\"manifest\":{\"version\":\"1\"}}");
        Files.writeString(stage.resolve("new.jar"), "abc");
        String hash = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        var manifest = new SyncManifest("1", List.of(new SyncManifest.ModFile("new.jar", hash, 3,
                URI.create("https://github.com/C0D0X/Neoforge-Sync/releases/download/v1/new.jar"))));
        Files.writeString(stage.resolve("manifest.json"), manifest.toJson());
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String classes = Path.of(Sleeper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        Process child = new ProcessBuilder(java, "-cp", classes, Sleeper.class.getName()).start();
        Process helper = null;
        try {
            long started = child.info().startInstant().orElseThrow().toEpochMilli();
            helper = HelperLauncher.launch(root, "stage-test", child.pid(), started);
            assertFalse(helper.waitFor(400, TimeUnit.MILLISECONDS));
            assertFalse(Files.exists(mods.resolve("new.jar")));
            child.destroyForcibly();
            assertTrue(child.waitFor(10, TimeUnit.SECONDS));
            assertTrue(helper.waitFor(10, TimeUnit.SECONDS));
            assertEquals(0, helper.exitValue(), Files.readString(work.resolve("helper.log")));
            assertEquals("abc", Files.readString(mods.resolve("new.jar")));
        } finally { child.destroyForcibly(); if (helper != null) helper.destroyForcibly(); }
    }
}
