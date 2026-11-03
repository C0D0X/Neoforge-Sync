package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/** Persists only mod names and hashes, never credentials or local user data. */
public final class ManagedState {
    private static final int MAX_BYTES = 512 * 1024;
    private ManagedState() {}

    public static Map<String, String> load(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed state is not a regular file");
        }
        try (var input = Files.newInputStream(file)) {
            byte[] data = input.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new IOException("Managed state too large");
            JsonObject root = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject();
            if (!root.has("schema") || !root.get("schema").isJsonPrimitive()
                    || !"1".equals(root.get("schema").getAsString())) throw new IOException("Unsupported managed state schema");
            JsonObject files = root.getAsJsonObject("mods");
            if (files.size() > 2000) throw new IOException("Too many managed files");
            Map<String, String> result = new TreeMap<>();
            for (var entry : files.entrySet()) {
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) throw new IOException("Invalid managed hash");
                result.put(entry.getKey(), entry.getValue().getAsString());
            }
            validate(result);
            return Map.copyOf(result);
        } catch (com.google.gson.JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
            throw new IOException("Invalid managed state", e);
        }
    }

    public static void save(Path file, Map<String, String> mods) throws IOException {
        validate(mods);
        Path parent = file.toAbsolutePath().getParent();
        if (Files.isSymbolicLink(parent) || !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Managed state directory is missing or is a symbolic link");
        }
        if (Files.isSymbolicLink(file)) throw new IOException("Managed state is a symbolic link");
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        JsonObject values = new JsonObject();
        new TreeMap<>(mods).forEach(values::addProperty);
        root.add("mods", values);
        byte[] bytes = root.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Managed state too large");
        Path temp = Files.createTempFile(parent, "managed-", ".tmp");
        try {
            Files.write(temp, bytes);
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void validate(Map<String, String> mods) throws IOException {
        if (mods == null || mods.size() > 2000) throw new IOException("Invalid managed state");
        var names = new HashSet<String>();
        for (var entry : mods.entrySet()) {
            String name = entry.getKey(), hash = entry.getValue();
            if (name == null || !name.matches("[A-Za-z0-9_+.-]{1,240}\\.jar") || name.contains("..")
                    || hash == null || !hash.matches("[a-f0-9]{64}") || !names.add(name.toLowerCase(Locale.ROOT))) {
                throw new IOException("Invalid managed mod entry");
            }
        }
    }
}
