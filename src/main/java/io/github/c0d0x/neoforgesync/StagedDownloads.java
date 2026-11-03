package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Stages verified files in a new private directory without touching live mods. */
public final class StagedDownloads {
    private StagedDownloads() {}

    @FunctionalInterface
    public interface Fetcher {
        InputStream open(URI url) throws IOException;
    }

    public static Path stage(SyncManifest manifest, Path parent, Fetcher fetcher) throws IOException {
        Files.createDirectories(parent);
        Path stage = Files.createTempDirectory(parent, "stage-");
        boolean complete = false;
        try {
            for (SyncManifest.ModFile mod : manifest.mods()) {
                // Validate even when the record was constructed directly rather than parsed.
                String name = mod.name();
                if (name == null || !name.matches("[A-Za-z0-9_+.-]{1,240}\\.jar") || name.contains("..")) {
                    throw new IOException("Invalid staged filename");
                }
                Path output = stage.resolve(name);
                MessageDigest digest = sha256();
                long count = 0;
                try (InputStream input = fetcher.open(mod.url()); OutputStream out = Files.newOutputStream(output, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        count += n;
                        if (count > mod.size()) throw new IOException("Download exceeds declared size: " + name);
                        digest.update(buffer, 0, n);
                        out.write(buffer, 0, n);
                    }
                }
                if (count != mod.size() || !HexFormat.of().formatHex(digest.digest()).equals(mod.sha256())) {
                    throw new IOException("Download size or hash mismatch: " + name);
                }
            }
            complete = true;
            return stage;
        } finally {
            if (!complete) {
                try (var files = Files.list(stage)) {
                    for (Path file : files.toList()) Files.deleteIfExists(file);
                }
                Files.deleteIfExists(stage);
            }
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
