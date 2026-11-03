package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/** Finds the most recent published sync release without requiring credentials. */
public final class GitHubSnapshots {
    private static final String API = "https://api.github.com/repos/C0D0X/Neoforge-Sync/releases?per_page=100&page=";
    private GitHubSnapshots() {}

    public static Optional<SyncManifest> latest() throws IOException {
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(20)).build();
        Candidate best = null;
        boolean finished = false;
        for (int page = 1; page <= 10; page++) {
            HttpRequest request = HttpRequest.newBuilder(URI.create(API + page)).timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "NeoForge-Sync/1").header("Accept", "application/vnd.github+json").GET().build();
            HttpResponse<java.io.InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Release check interrupted", e);
            }
            String text;
            try (var body = response.body()) {
                if (response.statusCode() != 200) throw new IOException("GitHub release check returned HTTP " + response.statusCode());
                byte[] bytes = body.readNBytes(4 * 1024 * 1024 + 1);
                if (bytes.length > 4 * 1024 * 1024) throw new IOException("Release listing too large");
                text = new String(bytes, StandardCharsets.UTF_8);
            }
            JsonArray releases;
            try { releases = JsonParser.parseString(text).getAsJsonArray(); }
            catch (RuntimeException e) { throw new IOException("Invalid GitHub release listing", e); }
            for (var element : releases) {
                Candidate candidate = candidate(element.getAsJsonObject());
                if (candidate != null && (best == null || candidate.published().isAfter(best.published()))) best = candidate;
            }
            if (releases.size() < 100) { finished = true; break; }
        }
        if (!finished) throw new IOException("Too many releases to check safely");
        if (best == null) return Optional.empty();
        URI url = URI.create("https://github.com/C0D0X/Neoforge-Sync/releases/download/" + best.tag() + "/manifest.json");
        try (var input = new GitHubDownloads().open(url)) {
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("Sync manifest too large");
            SyncManifest manifest = SyncManifest.parse(new String(bytes, StandardCharsets.UTF_8));
            String prefix = "/C0D0X/Neoforge-Sync/releases/download/" + best.tag() + "/";
            for (var mod : manifest.mods()) {
                if (!mod.url().getRawPath().startsWith(prefix)) throw new IOException("Manifest references a different release");
            }
            if (manifest.updater() != null && !manifest.updater().url().getRawPath().startsWith(prefix)) {
                throw new IOException("Updater references a different release");
            }
            return Optional.of(manifest);
        }
    }

    static Candidate candidate(JsonObject release) throws IOException {
        try {
            if (release.get("draft").getAsBoolean()) return null;
            String tag = release.get("tag_name").getAsString();
            if (!tag.matches("sync-[a-f0-9-]{36}")) return null;
            Instant published = Instant.parse(release.get("published_at").getAsString());
            int manifests = 0;
            for (var asset : release.getAsJsonArray("assets")) {
                if (asset.getAsJsonObject().get("name").getAsString().equals("manifest.json")) manifests++;
            }
            if (manifests != 1) throw new IOException("Sync release has missing or ambiguous manifest");
            return new Candidate(tag, published);
        } catch (RuntimeException e) {
            throw new IOException("Invalid GitHub release metadata", e);
        }
    }

    record Candidate(String tag, Instant published) {}
}
