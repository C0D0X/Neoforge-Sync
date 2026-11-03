package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonParser;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GitHubSnapshotsTest {
    @Test void selectsPublishedSyncReleaseWithManifest() throws Exception {
        var json = JsonParser.parseString("""
            {"draft":false,"tag_name":"sync-00000000-0000-0000-0000-000000000000",
             "published_at":"2026-11-03T00:00:00Z","assets":[{"name":"manifest.json"}]}
            """).getAsJsonObject();
        assertNotNull(GitHubSnapshots.candidate(json));
        json.addProperty("draft", true);
        assertNull(GitHubSnapshots.candidate(json));
    }

    @Test void rejectsIncompleteSyncRelease() {
        var json = JsonParser.parseString("""
            {"draft":false,"tag_name":"sync-00000000-0000-0000-0000-000000000000",
             "published_at":"2026-11-03T00:00:00Z","assets":[]}
            """).getAsJsonObject();
        assertThrows(IOException.class, () -> GitHubSnapshots.candidate(json));
    }
}
