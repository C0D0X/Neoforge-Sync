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

    @Test void picksNewestPublishedSyncAndSkipsDraftsAndUnrelatedReleases() throws Exception {
        var releases = JsonParser.parseString("""
            [
              {"draft":false,"tag_name":"sync-00000000-0000-0000-0000-000000000001",
               "published_at":"2026-11-01T00:00:00Z","assets":[{"name":"manifest.json"}]},
              {"draft":true,"tag_name":"sync-00000000-0000-0000-0000-000000000002",
               "published_at":null,"assets":[]},
              {"draft":false,"tag_name":"v1.0","published_at":"2026-11-03T00:00:00Z","assets":[]},
              {"draft":false,"tag_name":"sync-00000000-0000-0000-0000-000000000003",
               "published_at":"2026-11-02T00:00:00Z","assets":[{"name":"manifest.json"}]}
            ]
            """).getAsJsonArray();
        assertEquals("sync-00000000-0000-0000-0000-000000000003", GitHubSnapshots.newest(null, releases).tag());
    }

    @Test void rejectsNonObjectReleaseWithoutUncheckedException() {
        var releases = JsonParser.parseString("[null]").getAsJsonArray();
        assertThrows(IOException.class, () -> GitHubSnapshots.newest(null, releases));
    }
}
