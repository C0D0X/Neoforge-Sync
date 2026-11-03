package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict representation of the mod files in a published snapshot. */
public record SyncManifest(String packVersion, List<ModFile> mods, ModFile updater) {
    private static final long MAX_FILE_SIZE = 512L * 1024 * 1024;
    private static final int MAX_FILES = 2000;
    private static final String RELEASE_PREFIX = "/C0D0X/Neoforge-Sync/releases/download/";

    public SyncManifest {
        mods = List.copyOf(mods);
    }

    public SyncManifest(String packVersion, List<ModFile> mods) { this(packVersion, mods, null); }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("schema", 1);
        root.addProperty("packVersion", packVersion);
        JsonArray entries = new JsonArray();
        for (ModFile mod : mods) {
            entries.add(fileJson(mod));
        }
        root.add("mods", entries);
        if (updater != null) root.add("updater", fileJson(updater));
        return root.toString();
    }

    private static JsonObject fileJson(ModFile mod) {
        JsonObject item = new JsonObject();
        item.addProperty("name", mod.name());
        item.addProperty("sha256", mod.sha256());
        item.addProperty("size", mod.size());
        item.addProperty("url", mod.url().toString());
        return item;
    }

    public static SyncManifest parse(String json) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            if (integer(root.get("schema")) != 1) throw new IOException("Unsupported manifest schema");
            String version = string(root.get("packVersion"));
            if (!version.matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")) throw new IOException("Invalid pack version");
            JsonArray entries = root.getAsJsonArray("mods");
            if (entries.size() > MAX_FILES) throw new IOException("Too many mods");
            List<ModFile> mods = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (JsonElement entry : entries) {
                ModFile file = parseFile(entry.getAsJsonObject());
                if (!names.add(file.name().toLowerCase(Locale.ROOT))) throw new IOException("Duplicate mod filename");
                mods.add(file);
            }
            ModFile updater = null;
            if (root.has("updater")) {
                updater = parseFile(root.getAsJsonObject("updater"));
                if (!names.add(updater.name().toLowerCase(Locale.ROOT))) throw new IOException("Updater asset duplicates a mod");
            }
            return new SyncManifest(version, mods, updater);
        } catch (JsonParseException | IllegalStateException | NullPointerException | ClassCastException | NumberFormatException | URISyntaxException e) {
            throw new IOException("Invalid sync manifest", e);
        }
    }

    private static ModFile parseFile(JsonObject file) throws IOException, URISyntaxException {
        String name = string(file.get("name"));
        if (!name.matches("[A-Za-z0-9_+.-]{1,240}\\.jar") || name.contains("..")) throw new IOException("Invalid mod filename");
        String hash = string(file.get("sha256"));
        if (!hash.matches("[a-f0-9]{64}")) throw new IOException("Invalid SHA-256");
        long size = integer(file.get("size"));
        if (size <= 0 || size > MAX_FILE_SIZE) throw new IOException("Invalid mod size");
        URI url = new URI(string(file.get("url")));
        if (!"https".equals(url.getScheme()) || !"github.com".equals(url.getHost()) || url.getPort() != -1
                || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null
                || url.getRawPath() == null || !url.getRawPath().startsWith(RELEASE_PREFIX)
                || url.getRawPath().contains("%") || url.getRawPath().contains("..")) throw new IOException("Invalid release URL");
        String rest = url.getRawPath().substring(RELEASE_PREFIX.length());
        if (!rest.matches("[A-Za-z0-9_+.-]{1,100}/[A-Za-z0-9_+.-]{1,240}\\.jar")) throw new IOException("Invalid release asset path");
        return new ModFile(name, hash, size, url);
    }

    private static String string(JsonElement element) throws IOException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) throw new IOException("Expected string");
        return element.getAsString();
    }

    private static long integer(JsonElement element) throws IOException {
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()
                || !element.getAsString().matches("0|[1-9][0-9]{0,18}")) throw new IOException("Expected non-negative integer");
        try {
            return Long.parseLong(element.getAsString());
        } catch (NumberFormatException e) {
            throw new IOException("Integer out of range", e);
        }
    }

    public record ModFile(String name, String sha256, long size, URI url) {}
}
