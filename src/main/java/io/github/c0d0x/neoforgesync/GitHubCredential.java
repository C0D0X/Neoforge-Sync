package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** Uses the publisher's existing Git credential helper; no secrets are saved by the mod. */
public final class GitHubCredential {
    private GitHubCredential() {}

    public static String load() throws IOException {
        ProcessBuilder builder = new ProcessBuilder("git", "credential", "fill");
        builder.environment().put("GIT_TERMINAL_PROMPT", "0");
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start();
        try {
            try (var output = process.getOutputStream()) {
                output.write("protocol=https\nhost=github.com\n\n".getBytes(StandardCharsets.UTF_8));
            }
            if (!process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)) throw new IOException("Git credential helper timed out");
            if (process.exitValue() != 0) throw new IOException("GitHub credential unavailable");
            byte[] bytes = process.getInputStream().readNBytes(16385);
            if (bytes.length > 16384) throw new IOException("Git credential response too large");
            String password = null;
            for (String line : new String(bytes, StandardCharsets.UTF_8).split("\\r?\\n")) {
                if (line.startsWith("password=")) {
                    if (password != null) throw new IOException("Ambiguous GitHub credential");
                    password = line.substring("password=".length());
                }
            }
            if (password == null || password.isBlank()) throw new IOException("GitHub credential unavailable");
            return password;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Git credential lookup interrupted", e);
        } finally {
            process.destroyForcibly();
        }
    }
}
