package io.github.c0d0x.neoforgesync;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReceiverTest {
    @TempDir Path root;
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private final SyncManifest manifest = new SyncManifest("1.78.1", List.of(
            new SyncManifest.ModFile("extra.jar", HASH, 3, URI.create("https://github.com/a"))));

    private Path setup(String version) throws IOException {
        Files.createDirectory(root.resolve("mods"));
        Path work = Files.createDirectory(root.resolve("work"));
        Files.writeString(root.resolve("minecraftinstance.json"), "{\"manifest\":{\"version\":\"" + version + "\"}}");
        return work;
    }

    @Test void stagesUpdateWithoutTouchingLiveMods() throws Exception {
        Path work = setup("1.78.1");
        Files.writeString(root.resolve("mods/personal.jar"), "mine");
        var result = Receiver.prepare(root, work, () -> Optional.of(manifest),
                url -> new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        var ready = assertInstanceOf(Receiver.Ready.class, result);
        assertEquals("abc", Files.readString(ready.staging().resolve("extra.jar")));
        assertFalse(Files.exists(root.resolve("mods/extra.jar")));
        assertEquals("mine", Files.readString(root.resolve("mods/personal.jar")));
        assertFalse(Files.exists(work.resolve("managed.json")));
    }

    @Test void versionMismatchDoesNotDownload() throws Exception {
        Path work = setup("1.77");
        var result = Receiver.prepare(root, work, () -> Optional.of(manifest), url -> { throw new AssertionError("download called"); });
        var mismatch = assertInstanceOf(Receiver.VersionMismatch.class, result);
        assertEquals("1.77", mismatch.installed());
        assertEquals("1.78.1", mismatch.expected());
    }

    @Test void identicalManagedFileNeedsNoDownload() throws Exception {
        Path work = setup("1.78.1");
        Files.writeString(root.resolve("mods/extra.jar"), "abc");
        ManagedState.save(work.resolve("managed.json"), Map.of("extra.jar", HASH));
        var result = Receiver.prepare(root, work, () -> Optional.of(manifest), url -> { throw new AssertionError("download called"); });
        assertInstanceOf(Receiver.UpToDate.class, result);
    }

    @Test void rejectsUnmanagedCollision() throws Exception {
        Path work = setup("1.78.1");
        Files.writeString(root.resolve("mods/extra.jar"), "abc");
        assertThrows(IOException.class, () -> Receiver.prepare(root, work, () -> Optional.of(manifest), url -> { throw new AssertionError("download called"); }));
    }
}
