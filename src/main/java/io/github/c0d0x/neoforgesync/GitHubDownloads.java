package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Downloads release assets without following redirects to arbitrary hosts. */
public final class GitHubDownloads implements StagedDownloads.Fetcher {
    private static final Set<String> HOSTS = Set.of("github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com");
    private final HttpClient client = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(20))
            .build();

    @Override
    public InputStream open(URI url) throws IOException {
        URI current = url;
        for (int redirects = 0; redirects <= 5; redirects++) {
            validate(current);
            HttpRequest request = HttpRequest.newBuilder(current)
                    .timeout(Duration.ofMinutes(5))
                    .header("User-Agent", "NeoForge-Sync/1")
                    .GET().build();
            HttpResponse<InputStream> response;
            try {
                response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Download interrupted", e);
            }
            int code = response.statusCode();
            if (code == 200) return response.body();
            try (InputStream ignored = response.body()) {
                if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308) {
                    throw new IOException("GitHub download returned HTTP " + code);
                }
                if (redirects == 5) throw new IOException("Too many download redirects");
                String location = response.headers().firstValue("location").orElseThrow(() -> new IOException("Redirect missing location"));
                try {
                    current = current.resolve(location);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid download redirect", e);
                }
            }
        }
        throw new IOException("Too many download redirects");
    }

    static void validate(URI url) throws IOException {
        String host = url.getHost();
        if (!"https".equalsIgnoreCase(url.getScheme()) || host == null || !HOSTS.contains(host.toLowerCase(Locale.ROOT))
                || url.getUserInfo() != null || url.getPort() != -1 || url.getFragment() != null) {
            throw new IOException("Untrusted download destination");
        }
    }
}
