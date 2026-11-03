package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncManifestTest {
    private static final String HASH = "a".repeat(64);
    private static String entry(String name, String url) {
        return "{\"name\":\"" + name + "\",\"sha256\":\"" + HASH + "\",\"size\":42,\"url\":\"" + url + "\"}";
    }
    private static String manifest(String entries) {
        return "{\"schema\":1,\"packVersion\":\"1.78.1\",\"mods\":[" + entries + "]}";
    }
    private static final String URL = "https://github.com/C0D0X/Neoforge-Sync/releases/download/v1/mod.jar";

    @Test void acceptsValidManifest() throws Exception {
        var parsed = SyncManifest.parse(manifest(entry("mod.jar", URL)));
        assertEquals("1.78.1", parsed.packVersion());
        assertEquals("mod.jar", parsed.mods().getFirst().name());
    }

    @Test void rejectsPathsAndDuplicateNames() {
        assertThrows(IOException.class, () -> SyncManifest.parse(manifest(entry("../mod.jar", URL))));
        assertThrows(IOException.class, () -> SyncManifest.parse(manifest(entry("Mod.jar", URL) + "," + entry("mod.jar", URL))));
    }

    @Test void rejectsUntrustedUrls() {
        assertThrows(IOException.class, () -> SyncManifest.parse(manifest(entry("mod.jar", URL.replace("github.com", "evil.example")))));
        assertThrows(IOException.class, () -> SyncManifest.parse(manifest(entry("mod.jar", URL + "?redirect=evil"))));
        assertThrows(IOException.class, () -> SyncManifest.parse(manifest(entry("mod.jar", URL.replace("/v1/", "/%2e%2e/")))));
    }

    @Test void rejectsBadHashAndSize() {
        assertThrows(IOException.class, () -> SyncManifest.parse(manifest(entry("mod.jar", URL).replace(HASH, "short"))));
        assertThrows(IOException.class, () -> SyncManifest.parse(manifest(entry("mod.jar", URL).replace("\"size\":42", "\"size\":0"))));
    }
}
