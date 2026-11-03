package io.github.c0d0x.neoforgesync;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in: publishes a temporary isolated-version release, then removes release and tag. */
class LiveGitHubTest {
    @TempDir Path root;

    @Test void realReleaseCanBeDiscoveredDownloadedAndApplied() throws Exception {
        assumeTrue("1".equals(System.getenv("NEOFORGE_SYNC_LIVE_TEST")));
        Path source = Files.createDirectory(root.resolve("source"));
        Path temp = Files.createDirectory(root.resolve("temp"));
        Path receiver = Files.createDirectory(root.resolve("receiver"));
        Path mods = Files.createDirectory(receiver.resolve("mods"));
        Path work = Files.createDirectory(receiver.resolve(".neoforgesync"));
        Files.writeString(source.resolve("isolated-test.jar"), "isolated test asset");
        Files.writeString(receiver.resolve("minecraftinstance.json"), "{\"manifest\":{\"version\":\"isolated-test\"}}");
        var real = new GitHubReleaseGateway(GitHubCredential.load());
        class TrackingGateway implements ReleasePublisher.Gateway {
            long id;
            String tag;
            boolean published;
            public long createDraft(String value) throws java.io.IOException { tag = value; return id = real.createDraft(value); }
            public void upload(long id, String name, Path file, String type) throws java.io.IOException { real.upload(id, name, file, type); }
            public void publish(long id) throws java.io.IOException { real.publish(id); published = true; }
            public void deleteDraft(long id) throws java.io.IOException { real.deleteDraft(id); this.id = 0; }
        }
        var gateway = new TrackingGateway();
        try {
            ReleasePublisher.publish(source, temp, "isolated-test", ModInventory.scan(source), gateway);
            assertTrue(gateway.published);
            var ready = assertInstanceOf(Receiver.Ready.class, Receiver.prepare(receiver, work, GitHubSnapshots::latest, new GitHubDownloads()));
            RecoverableApply.apply(mods, ready.staging(), work, ready.manifest());
            assertEquals("isolated test asset", Files.readString(mods.resolve("isolated-test.jar")));
            assertInstanceOf(Receiver.UpToDate.class, Receiver.prepare(receiver, work, GitHubSnapshots::latest, new GitHubDownloads()));
        } finally {
            if (gateway.id != 0) real.deleteDraft(gateway.id);
            if (gateway.published) real.deleteTestTag(gateway.tag);
        }
    }
}
