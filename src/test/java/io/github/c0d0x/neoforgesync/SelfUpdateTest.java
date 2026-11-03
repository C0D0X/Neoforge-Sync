package io.github.c0d0x.neoforgesync;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SelfUpdateTest {
    @TempDir Path root;
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private final SyncManifest.ModFile updater = new SyncManifest.ModFile("publisher-sync.jar", ABC, 3,
            URI.create("https://github.com/C0D0X/Neoforge-Sync/releases/download/v1/publisher-sync.jar"));

    @Test void updaterManifestRoundTripsAndRejectsLocalNameCollision() throws Exception {
        var manifest = new SyncManifest("1", List.of(), updater);
        assertEquals(manifest, SyncManifest.parse(manifest.toJson()));
        var collision = new SyncManifest("1", List.of(new SyncManifest.ModFile("SYNC.jar", ABC, 3, updater.url())), updater);
        assertThrows(java.io.IOException.class, () -> SelfUpdate.effective(collision, "sync.jar"));
    }

    @Test void separateCopiedHelperReplacesInstalledUpdaterWithoutManagingItAsAMod() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path work = Files.createDirectory(root.resolve(".neoforgesync"));
        Files.writeString(root.resolve("minecraftinstance.json"), "{\"manifest\":{\"version\":\"1\"}}");
        Path installed = mods.resolve("sync.jar");
        Path classes = Path.of(PostExitHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        writeJar(classes, installed, false);
        Path newDirectory = Files.createDirectory(root.resolve("new-version"));
        Path next = newDirectory.resolve("next.jar");
        writeJar(classes, next, true);
        byte[] nextBytes = Files.readAllBytes(next);
        String nextHash = ModInventory.scan(newDirectory).get("next.jar");
        Path copy = work.resolve("helper.jar");
        Files.copy(installed, copy);
        var nextUpdater = new SyncManifest.ModFile("publisher-sync.jar", nextHash, nextBytes.length, updater.url());
        SyncManifest manifest = new SyncManifest("1", List.of(), nextUpdater);
        var result = Receiver.prepare(root, work, () -> Optional.of(manifest),
                uri -> new ByteArrayInputStream(nextBytes), "sync.jar");
        var ready = assertInstanceOf(Receiver.Ready.class, result);
        assertTrue(Files.exists(ready.staging().resolve("sync.jar")));
        String jvm = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String gson = Path.of(com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        long pid = ProcessHandle.current().pid();
        long differentStart = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() + 1;
        Path log = root.resolve("helper.log");
        Process helper = new ProcessBuilder(jvm, "-cp", copy + System.getProperty("path.separator") + gson,
                PostExitHelper.class.getName(), root.toString(), ready.staging().getFileName().toString(),
                Long.toString(pid), Long.toString(differentStart), "sync.jar")
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(helper.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, helper.exitValue(), Files.readString(log));
        assertArrayEquals(nextBytes, Files.readAllBytes(installed));
        assertEquals(Map.of(), ManagedState.load(work.resolve("managed.json")));
        assertFalse(java.util.Arrays.equals(nextBytes, Files.readAllBytes(copy)));
        assertInstanceOf(Receiver.UpToDate.class, Receiver.prepare(root, work, () -> Optional.of(manifest),
                uri -> { throw new AssertionError("identical updater downloaded again"); }, "sync.jar"));
    }

    private static void writeJar(Path classes, Path target, boolean marker) throws Exception {
        try (var out = new JarOutputStream(Files.newOutputStream(target)); var paths = Files.walk(classes)) {
            for (Path file : paths.filter(Files::isRegularFile).toList()) {
                out.putNextEntry(new JarEntry(classes.relativize(file).toString().replace('\\', '/')));
                Files.copy(file, out);
                out.closeEntry();
            }
            if (marker) {
                out.putNextEntry(new JarEntry("new-version-marker"));
                out.write(1);
                out.closeEntry();
            }
        }
    }

    @Test void interruptedSelfUpdateRestoresOriginalUpdaterAndState() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path work = Files.createDirectory(root.resolve("work"));
        Path stage = Files.createDirectory(root.resolve("stage"));
        Files.writeString(mods.resolve("sync.jar"), "old updater");
        Files.writeString(stage.resolve("sync.jar"), "abc");
        var manifest = new SyncManifest("1", List.of(), updater);
        assertThrows(java.io.IOException.class, () -> RecoverableApply.apply(mods, stage, work, manifest, "sync.jar", phase -> {
            if (phase.equals("install:sync.jar")) throw new java.io.IOException("interrupted");
        }));
        assertEquals("abc", Files.readString(mods.resolve("sync.jar")));
        assertTrue(RecoverableApply.recover(mods, work));
        assertEquals("old updater", Files.readString(mods.resolve("sync.jar")));
        assertEquals(Map.of(), ManagedState.load(work.resolve("managed.json")));
    }
}
