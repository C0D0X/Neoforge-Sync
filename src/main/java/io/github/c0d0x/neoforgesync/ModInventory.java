package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/** Read-only inventory; it does not decide which files are safe to publish. */
public final class ModInventory {
    private ModInventory() {}

    public static Map<String, String> scan(Path modsDirectory) throws IOException {
        if (Files.isSymbolicLink(modsDirectory) || !Files.isDirectory(modsDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Mods directory is missing or is a symbolic link: " + modsDirectory);
        }
        Map<String, String> result = new TreeMap<>();
        try (var paths = Files.list(modsDirectory)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) continue;
                if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Mod JAR is not a regular file: " + path);
                }
                result.put(name, hash(path));
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private static String hash(Path path) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[65536];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
