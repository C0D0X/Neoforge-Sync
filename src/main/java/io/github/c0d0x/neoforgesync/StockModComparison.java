package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Compares stock CurseForge metadata to an instance without changing it. */
public final class StockModComparison {
    private StockModComparison() {}

    public static Map<String, String> additions(Path instance) throws IOException {
        try {
            JsonObject manifest = json(instance.resolve("stock/manifest.json"));
            JsonObject info = json(instance.resolve("minecraftinstance.json"));
            if (!manifest.get("version").getAsString().equals(info.getAsJsonObject("manifest").get("version").getAsString())
                    || !manifest.get("minecraft").getAsJsonObject().get("version").getAsString().equals(info.getAsJsonObject("manifest").getAsJsonObject("minecraft").get("version").getAsString())) {
                throw new IOException("Stock export and instance versions differ");
            }
            Map<String, JsonObject> addons = new HashMap<>();
            for (var element : info.getAsJsonArray("installedAddons")) {
                JsonObject addon = element.getAsJsonObject();
                if (!addon.has("installedFile") || !addon.get("installedFile").isJsonObject()) continue;
                String key = addon.get("addonID").getAsString() + ":" + addon.getAsJsonObject("installedFile").get("id").getAsString();
                if (addons.putIfAbsent(key, addon) != null) throw new IOException("Duplicate installed addon: " + key);
            }
            Path mods = instance.resolve("mods");
            Map<String, String> installed = ModInventory.scan(mods);
            Set<String> stock = new HashSet<>();
            for (var element : manifest.getAsJsonArray("files")) {
                JsonObject entry = element.getAsJsonObject();
                String key = entry.get("projectID").getAsString() + ":" + entry.get("fileID").getAsString();
                JsonObject addon = addons.get(key);
                if (addon == null) throw new IOException("Stock manifest entry missing from CurseForge metadata: " + key);
                String category = addon.getAsJsonObject("categorySection").get("path").getAsString();
                if (Set.of("resourcepacks", "shaderpacks", "datapacks").contains(category)) continue;
                if (!category.equals("mods")) throw new IOException("Unknown stock category: " + category);
                String name = addon.get("fileNameOnDisk").getAsString();
                if (!installed.containsKey(name) || !stock.add(name)) throw new IOException("Missing or duplicate stock mod: " + name);
                String expected = sha1(addon.getAsJsonObject("installedFile").getAsJsonArray("hashes"));
                if (!expected.equalsIgnoreCase(digest(mods.resolve(name), "SHA-1"))) throw new IOException("Stock mod content differs: " + name);
            }
            Path overrides = instance.resolve("stock/overrides/mods");
            if (Files.exists(overrides, LinkOption.NOFOLLOW_LINKS)) {
                for (var item : ModInventory.scan(overrides).entrySet()) {
                    if (!item.getValue().equals(installed.get(item.getKey()))) throw new IOException("Stock override differs or is missing: " + item.getKey());
                    stock.add(item.getKey());
                }
            }
            Map<String, String> additions = new TreeMap<>(installed);
            stock.forEach(additions::remove);
            return Map.copyOf(additions);
        } catch (IllegalStateException | NullPointerException | ClassCastException | UnsupportedOperationException e) {
            throw new IOException("Invalid CurseForge metadata", e);
        }
    }

    private static JsonObject json(Path path) throws IOException {
        try (Reader reader = Files.newBufferedReader(path)) {
            return JsonParser.parseReader(reader).getAsJsonObject();
        } catch (com.google.gson.JsonParseException e) {
            throw new IOException("Invalid JSON: " + path, e);
        }
    }

    private static String sha1(JsonArray hashes) throws IOException {
        String found = null;
        for (var element : hashes) {
            JsonObject hash = element.getAsJsonObject();
            if (hash.get("type").getAsInt() == 1) {
                String value = hash.get("value").getAsString();
                if (found != null || !value.matches("(?i)[0-9a-f]{40}")) throw new IOException("Invalid SHA-1 metadata");
                found = value;
            }
        }
        if (found == null) throw new IOException("Stock file has no SHA-1 metadata");
        return found;
    }

    private static String digest(Path path, String algorithm) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
