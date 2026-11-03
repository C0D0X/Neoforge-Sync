package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Determines and stages updates without modifying live mod files. */
public final class Receiver {
    private Receiver() {}

    @FunctionalInterface public interface Source { Optional<SyncManifest> latest() throws IOException; }

    public sealed interface Result permits NoSnapshot, VersionMismatch, UpToDate, Ready {}
    public record NoSnapshot() implements Result {}
    public record VersionMismatch(String installed, String expected) implements Result {}
    public record UpToDate() implements Result {}
    public record Ready(SyncManifest manifest, Map<String, String> previous, SyncPlan plan, Path staging) implements Result {}

    public static Result prepare(Path instance, Path work, Source source, StagedDownloads.Fetcher fetcher) throws IOException {
        return prepare(instance, work, source, fetcher, null);
    }

    public static Result prepare(Path instance, Path work, Source source, StagedDownloads.Fetcher fetcher, String ownJar) throws IOException {
        if (Files.isSymbolicLink(work) || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Sync working directory must be a real directory");
        }
        Optional<SyncManifest> latest = source.latest();
        if (latest.isEmpty()) return new NoSnapshot();
        SyncManifest manifest = latest.get();
        String installed = packVersion(instance.resolve("minecraftinstance.json"));
        if (!manifest.packVersion().equals(installed)) return new VersionMismatch(installed, manifest.packVersion());
        Map<String, String> previous = ManagedState.load(work.resolve("managed.json"));
        Map<String, String> current = ModInventory.scan(instance.resolve("mods"));
        SyncManifest effective = SelfUpdate.effective(manifest, ownJar);
        var planningPrevious = new java.util.TreeMap<>(previous);
        if (manifest.updater() != null) {
            String currentUpdater = current.get(ownJar);
            if (currentUpdater == null) throw new IOException("Installed updater JAR is missing");
            if (planningPrevious.containsKey(ownJar)) throw new IOException("Updater is unexpectedly tracked as a regular mod");
            planningPrevious.put(ownJar, currentUpdater);
        }
        SyncPlan plan = SyncPlan.calculate(planningPrevious, current, effective);
        if (plan.install().isEmpty() && plan.remove().isEmpty()) return new UpToDate();
        var downloads = effective.mods().stream().filter(file -> plan.install().contains(file.name())).toList();
        Path staged = StagedDownloads.stage(new SyncManifest(manifest.packVersion(), downloads), work, fetcher);
        Files.writeString(staged.resolve("manifest.json"), manifest.toJson(), java.nio.file.StandardOpenOption.CREATE_NEW);
        return new Ready(manifest, previous, plan, staged);
    }

    static String packVersion(Path metadata) throws IOException {
        if (Files.isSymbolicLink(metadata) || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("CurseForge instance metadata missing");
        }
        try (var input = Files.newInputStream(metadata)) {
            byte[] bytes = input.readNBytes(32 * 1024 * 1024 + 1);
            if (bytes.length > 32 * 1024 * 1024) throw new IOException("CurseForge metadata too large");
            var version = JsonParser.parseString(new String(bytes, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("manifest").get("version");
            if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isString()
                    || !version.getAsString().matches("[A-Za-z0-9][A-Za-z0-9._+-]{0,63}")) throw new IOException("Invalid CurseForge pack version");
            return version.getAsString();
        } catch (com.google.gson.JsonParseException | IllegalStateException | NullPointerException | ClassCastException e) {
            throw new IOException("Invalid CurseForge instance metadata", e);
        }
    }
}
