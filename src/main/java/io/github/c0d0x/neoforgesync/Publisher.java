package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Compares the publisher's additions with the last complete published snapshot. */
public final class Publisher {
    private Publisher() {}

    public enum Result { UNCHANGED, PUBLISHED }

    public static Result run(Path instance, Path work, String ownJar, Receiver.Source source,
                             ReleasePublisher.Gateway gateway) throws IOException {
        if (Files.isSymbolicLink(work) || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Sync working directory must be a real directory");
        }
        Map<String, String> additions = new TreeMap<>(StockModComparison.additions(instance));
        String updaterHash = ownJar == null ? null : additions.remove(ownJar);
        if (ownJar != null && updaterHash == null) throw new IOException("Installed syncer JAR could not be identified as an addition");
        String version = stockVersion(instance.resolve("stock/manifest.json"));
        Optional<SyncManifest> latest = source.latest();
        if (latest.isPresent() && matches(latest.get(), version, additions)
                && (updaterHash == null ? latest.get().updater() == null
                    : latest.get().updater() != null && updaterHash.equals(latest.get().updater().sha256()))) return Result.UNCHANGED;
        ReleasePublisher.publish(instance.resolve("mods"), work, version, additions, ownJar, gateway);
        return Result.PUBLISHED;
    }

    static boolean matches(SyncManifest manifest, String version, Map<String, String> additions) {
        if (!version.equals(manifest.packVersion()) || manifest.mods().size() != additions.size()) return false;
        Map<String, String> published = new TreeMap<>();
        for (var mod : manifest.mods()) {
            if (published.putIfAbsent(mod.name(), mod.sha256()) != null) return false;
        }
        return published.equals(additions);
    }

    private static String stockVersion(Path file) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Stock manifest missing");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(4 * 1024 * 1024 + 1);
            if (bytes.length > 4 * 1024 * 1024) throw new IOException("Stock manifest too large");
            var value = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject().get("version");
            if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
                    || !value.getAsString().matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")) throw new IOException("Invalid stock pack version");
            return value.getAsString();
        } catch (com.google.gson.JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
            throw new IOException("Invalid stock manifest", e);
        }
    }
}
