package io.github.c0d0x.neoforgesync;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StagedDownloadsTest {
    @TempDir Path root;
    private static final String HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    private SyncManifest manifest(String hash, int size) throws IOException {
        return SyncManifest.parse("{\"schema\":1,\"packVersion\":\"1\",\"mods\":[{\"name\":\"mod.jar\",\"sha256\":\"" + hash
                + "\",\"size\":" + size + ",\"url\":\"https://github.com/C0D0X/Neoforge-Sync/releases/download/v1/mod.jar\"}]}");
    }

    @Test void stagesVerifiedFile() throws Exception {
        var path = StagedDownloads.stage(manifest(HASH, 3), root, url -> new ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8)));
        assertEquals("abc", Files.readString(path.resolve("mod.jar")));
    }

    @Test void rejectsBadContentAndRemovesPartialStage() throws Exception {
        assertThrows(IOException.class, () -> StagedDownloads.stage(manifest(HASH, 3), root, url -> new ByteArrayInputStream("bad".getBytes(StandardCharsets.UTF_8))));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }

    @Test void rejectsOversizedDownloadAndRemovesPartialStage() throws Exception {
        assertThrows(IOException.class, () -> StagedDownloads.stage(manifest(HASH, 3), root, url -> new ByteArrayInputStream("abcd".getBytes(StandardCharsets.UTF_8))));
        try (var files = Files.list(root)) { assertEquals(0, files.count()); }
    }
}
