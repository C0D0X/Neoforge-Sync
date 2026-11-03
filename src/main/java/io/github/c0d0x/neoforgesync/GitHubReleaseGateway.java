package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/** GitHub release publisher. This class never follows authenticated redirects. */
public final class GitHubReleaseGateway implements ReleasePublisher.Gateway {
    private static final String REPO = "/repos/C0D0X/Neoforge-Sync/releases";
    private final String token;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20)).build();

    public GitHubReleaseGateway(String token) {
        if (token == null || token.isBlank() || token.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid GitHub credential");
        }
        this.token = token;
    }

    @Override
    public long createDraft(String tag) throws IOException {
        if (!tag.matches("sync-[a-f0-9-]{36}")) throw new IOException("Invalid release tag");
        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        body.addProperty("name", tag);
        body.addProperty("draft", true);
        body.addProperty("prerelease", true);
        String result = send("POST", URI.create("https://api.github.com" + REPO),
                HttpRequest.BodyPublishers.ofString(body.toString()), "application/json", 201);
        try {
            long id = JsonParser.parseString(result).getAsJsonObject().get("id").getAsLong();
            if (id <= 0) throw new IOException("Invalid GitHub release ID");
            return id;
        } catch (RuntimeException e) {
            throw new IOException("Invalid GitHub release response", e);
        }
    }

    @Override
    public void upload(long releaseId, String name, Path file, String contentType) throws IOException {
        checkId(releaseId);
        if (!name.matches("[A-Za-z0-9_+.-]{1,245}") || name.contains("..")) throw new IOException("Invalid asset name");
        if (!contentType.equals("application/java-archive") && !contentType.equals("application/json")) throw new IOException("Invalid asset content type");
        // '+' is encoded so GitHub does not interpret it as a space in the query.
        String encodedName = name.replace("+", "%2B");
        URI uri = URI.create("https://uploads.github.com" + REPO + "/" + releaseId + "/assets?name=" + encodedName);
        send("POST", uri, HttpRequest.BodyPublishers.ofFile(file), contentType, 201);
    }

    @Override
    public void publish(long releaseId) throws IOException {
        checkId(releaseId);
        send("PATCH", URI.create("https://api.github.com" + REPO + "/" + releaseId),
                HttpRequest.BodyPublishers.ofString("{\"draft\":false}"), "application/json", 200);
    }

    @Override
    public void deleteDraft(long releaseId) throws IOException {
        checkId(releaseId);
        send("DELETE", URI.create("https://api.github.com" + REPO + "/" + releaseId),
                HttpRequest.BodyPublishers.noBody(), "application/json", 204);
    }

    private static void checkId(long id) throws IOException {
        if (id <= 0) throw new IOException("Invalid release ID");
    }

    private String send(String method, URI uri, HttpRequest.BodyPublisher body, String contentType, int expected) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "NeoForge-Sync/1")
                .header("Content-Type", contentType)
                .method(method, body).build();
        HttpResponse<java.io.InputStream> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("GitHub request interrupted", e);
        }
        try (var stream = response.body()) {
            if (response.statusCode() != expected) throw new IOException("GitHub returned HTTP " + response.statusCode());
            byte[] bytes = stream.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("GitHub response too large");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }
}
