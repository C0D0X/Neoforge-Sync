package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

/** Creates a draft, uploads a complete snapshot, and publishes it last. */
public final class ReleasePublisher {
    private ReleasePublisher() {}

    public interface Gateway {
        long createDraft(String tag) throws IOException;
        void upload(long releaseId, String name, Path file, String contentType) throws IOException;
        void publish(long releaseId) throws IOException;
        void deleteDraft(long releaseId) throws IOException;
    }

    public static String publish(Path mods, Path temporaryParent, String packVersion,
                                 Map<String, String> additions, Gateway gateway) throws IOException {
        return publish(mods, temporaryParent, packVersion, additions, null, gateway);
    }

    public static String publish(Path mods, Path temporaryParent, String packVersion,
                                 Map<String, String> additions, String updaterName, Gateway gateway) throws IOException {
        if (!Files.isDirectory(temporaryParent, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(temporaryParent)) {
            throw new IOException("Temporary parent must be a real directory");
        }
        Map<String, String> original = ModInventory.scan(mods);
        Map<String, String> toPublish = new java.util.TreeMap<>(additions);
        if (updaterName != null) {
            String hash = original.get(updaterName);
            if (hash == null || toPublish.putIfAbsent(updaterName, hash) != null) throw new IOException("Updater JAR missing or duplicated");
        }
        for (var entry : toPublish.entrySet()) {
            if (!entry.getKey().matches("[A-Za-z0-9_+.-]{1,240}\\.jar") || entry.getKey().contains("..")
                    || !entry.getValue().equals(original.get(entry.getKey()))) {
                throw new IOException("Invalid or changed addition: " + entry.getKey());
            }
        }
        Path snapshot = Files.createTempDirectory(temporaryParent, "publish-");
        Long release = null;
        boolean published = false;
        boolean publishAttempted = false;
        try {
            JsonObject manifest = new JsonObject();
            manifest.addProperty("schema", 1);
            manifest.addProperty("packVersion", packVersion);
            JsonArray entries = new JsonArray();
            String tag = "sync-" + UUID.randomUUID();
            for (var entry : new java.util.TreeMap<>(toPublish).entrySet()) {
                Path target = snapshot.resolve(entry.getKey());
                Files.copy(mods.resolve(entry.getKey()), target);
                JsonObject file = new JsonObject();
                file.addProperty("name", entry.getKey());
                file.addProperty("sha256", entry.getValue());
                file.addProperty("size", Files.size(target));
                file.addProperty("url", "https://github.com/C0D0X/Neoforge-Sync/releases/download/" + tag + "/" + entry.getKey());
                if (entry.getKey().equals(updaterName)) manifest.add("updater", file);
                else entries.add(file);
            }
            if (!ModInventory.scan(snapshot).equals(toPublish)) throw new IOException("Mods changed while preparing publication");
            manifest.add("mods", entries);
            String json = manifest.toString();
            SyncManifest.parse(json);
            Path manifestFile = snapshot.resolve("manifest.json");
            Files.writeString(manifestFile, json);
            release = gateway.createDraft(tag);
            for (String name : new java.util.TreeSet<>(toPublish.keySet())) {
                gateway.upload(release, name, snapshot.resolve(name), "application/java-archive");
            }
            gateway.upload(release, "manifest.json", manifestFile, "application/json");
            publishAttempted = true;
            gateway.publish(release);
            published = true;
            return tag;
        } finally {
            // A failed publish request can have succeeded remotely; never delete it in that case.
            if (release != null && !published && !publishAttempted) {
                try { gateway.deleteDraft(release); } catch (IOException ignored) { }
            }
            try (var files = Files.list(snapshot)) {
                for (Path file : files.toList()) Files.deleteIfExists(file);
            }
            Files.deleteIfExists(snapshot);
        }
    }
}
