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
import java.util.TreeSet;

/** Read-only snapshots of config files; callers decide which paths to publish. */
public final class ConfigInventory {
    private ConfigInventory() {}

    public static Map<String, String> scan(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Config directory is missing or is a symbolic link: " + directory);
        }
        Map<String, String> files = new TreeMap<>();
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.toList()) {
                if (path.equals(directory)) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("Symbolic link in config directory: " + path);
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Not a regular config file: " + path);
                String name = directory.relativize(path).toString().replace(path.getFileSystem().getSeparator(), "/");
                files.put(name, hash(path));
            }
        }
        return Collections.unmodifiableMap(files);
    }

    public static Changes compare(Map<String, String> baseline, Map<String, String> current) {
        var added = new TreeSet<String>();
        var changed = new TreeSet<String>();
        var removed = new TreeSet<String>();
        current.forEach((path, hash) -> {
            if (!baseline.containsKey(path)) added.add(path);
            else if (!java.util.Objects.equals(hash, baseline.get(path))) changed.add(path);
        });
        baseline.keySet().stream().filter(path -> !current.containsKey(path)).forEach(removed::add);
        return new Changes(java.util.Set.copyOf(added), java.util.Set.copyOf(changed), java.util.Set.copyOf(removed));
    }

    public record Changes(java.util.Set<String> added, java.util.Set<String> changed, java.util.Set<String> removed) {}

    private static String hash(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
