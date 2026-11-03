package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.net.URI;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitHubDownloadsTest {
    @Test void acceptsOnlyHttpsGitHubAssetHosts() throws Exception {
        GitHubDownloads.validate(URI.create("https://github.com/example"));
        GitHubDownloads.validate(URI.create("https://release-assets.githubusercontent.com/asset?signature=x"));
        assertThrows(IOException.class, () -> GitHubDownloads.validate(URI.create("http://github.com/example")));
        assertThrows(IOException.class, () -> GitHubDownloads.validate(URI.create("https://github.com.evil.example/example")));
        assertThrows(IOException.class, () -> GitHubDownloads.validate(URI.create("https://github.com@evil.example/example")));
        assertThrows(IOException.class, () -> GitHubDownloads.validate(URI.create("https://github.com:444/example")));
    }
}
