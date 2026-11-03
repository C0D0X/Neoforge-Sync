package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ReleasePublisherTest {
    @TempDir Path root;

    static class FakeGateway implements ReleasePublisher.Gateway {
        final List<String> calls = new ArrayList<>();
        boolean failUpload;
        boolean failPublish;
        public long createDraft(String tag) { calls.add("draft"); return 7; }
        public void upload(long id, String name, Path file, String type) throws IOException {
            calls.add(name);
            if (failUpload) throw new IOException("failed");
            if (name.equals("manifest.json")) SyncManifest.parse(Files.readString(file));
        }
        public void publish(long id) throws IOException { calls.add("publish"); if (failPublish) throw new IOException("unknown publish outcome"); }
        public void deleteDraft(long id) { calls.add("delete"); }
    }

    @Test void uploadsManifestLastAndPublishesAfterwards() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path tmp = Files.createDirectory(root.resolve("tmp"));
        Files.writeString(mods.resolve("extra.jar"), "abc");
        var gateway = new FakeGateway();
        ReleasePublisher.publish(mods, tmp, "1.78.1", ModInventory.scan(mods), gateway);
        assertEquals(List.of("draft", "extra.jar", "manifest.json", "publish"), gateway.calls);
        try (var files = Files.list(tmp)) { assertEquals(0, files.count()); }
    }

    @Test void deletesDraftOnUploadFailure() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path tmp = Files.createDirectory(root.resolve("tmp"));
        Files.writeString(mods.resolve("extra.jar"), "abc");
        var gateway = new FakeGateway();
        gateway.failUpload = true;
        assertThrows(IOException.class, () -> ReleasePublisher.publish(mods, tmp, "1.78.1", ModInventory.scan(mods), gateway));
        assertEquals(List.of("draft", "extra.jar", "delete"), gateway.calls);
        try (var files = Files.list(tmp)) { assertEquals(0, files.count()); }
    }

    @Test void refusesStaleInventoryBeforeCreatingDraft() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path tmp = Files.createDirectory(root.resolve("tmp"));
        Files.writeString(mods.resolve("extra.jar"), "abc");
        var snapshot = ModInventory.scan(mods);
        Files.writeString(mods.resolve("extra.jar"), "changed");
        var gateway = new FakeGateway();
        assertThrows(IOException.class, () -> ReleasePublisher.publish(mods, tmp, "1.78.1", snapshot, gateway));
        assertTrue(gateway.calls.isEmpty());
    }

    @Test void doesNotDeleteReleaseWhenPublishOutcomeIsUnknown() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path tmp = Files.createDirectory(root.resolve("tmp"));
        Files.writeString(mods.resolve("extra.jar"), "abc");
        var gateway = new FakeGateway();
        gateway.failPublish = true;
        assertThrows(IOException.class, () -> ReleasePublisher.publish(mods, tmp, "1.78.1", ModInventory.scan(mods), gateway));
        assertEquals(List.of("draft", "extra.jar", "manifest.json", "publish"), gateway.calls);
    }

    @Test void publishesUpdaterAsSeparateManifestField() throws Exception {
        Path mods = Files.createDirectory(root.resolve("mods"));
        Path tmp = Files.createDirectory(root.resolve("tmp"));
        Files.writeString(mods.resolve("sync.jar"), "updater");
        var gateway = new FakeGateway() {
            @Override public void upload(long id, String name, Path file, String type) throws IOException {
                if (name.equals("manifest.json")) {
                    var manifest = SyncManifest.parse(Files.readString(file));
                    assertEquals("sync.jar", manifest.updater().name());
                    assertTrue(manifest.mods().isEmpty());
                }
                super.upload(id, name, file, type);
            }
        };
        ReleasePublisher.publish(mods, tmp, "1.78.1", java.util.Map.of(), "sync.jar", gateway);
        assertEquals(List.of("draft", "sync.jar", "manifest.json", "publish"), gateway.calls);
    }
}
