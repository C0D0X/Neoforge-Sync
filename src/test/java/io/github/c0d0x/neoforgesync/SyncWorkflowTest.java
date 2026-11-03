package io.github.c0d0x.neoforgesync;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises publication, verified staging, the separate JVM helper, and managed removal. */
class SyncWorkflowTest {
    @TempDir Path root;

    static final class FakeReleases implements ReleasePublisher.Gateway, Receiver.Source, StagedDownloads.Fetcher {
        Map<String, byte[]> draft;
        Map<String, byte[]> published;
        int releases;
        public long createDraft(String tag) { draft = new HashMap<>(); return ++releases; }
        public void upload(long id, String name, Path file, String type) throws IOException { draft.put(name, Files.readAllBytes(file)); }
        public void publish(long id) { published = Map.copyOf(draft); }
        public void deleteDraft(long id) { draft = null; }
        public Optional<SyncManifest> latest() throws IOException {
            return published == null ? Optional.empty() : Optional.of(SyncManifest.parse(new String(published.get("manifest.json"), java.nio.charset.StandardCharsets.UTF_8)));
        }
        public ByteArrayInputStream open(URI uri) throws IOException {
            String name = uri.getPath().substring(uri.getPath().lastIndexOf('/') + 1);
            byte[] bytes = published.get(name);
            if (bytes == null) throw new IOException("missing asset");
            return new ByteArrayInputStream(bytes);
        }
    }

    @Test void publishesInstallsAndThenRemovesAModWithoutTouchingPersonalFiles() throws Exception {
        Path publisher = Files.createDirectory(root.resolve("publisher"));
        Path publisherMods = Files.createDirectory(publisher.resolve("mods"));
        Path stock = Files.createDirectory(publisher.resolve("stock"));
        Path publisherWork = Files.createDirectory(root.resolve("publisher-work"));
        Files.writeString(publisherMods.resolve("stock.jar"), "abc");
        Files.writeString(publisherMods.resolve("extra.jar"), "xyz");
        Files.writeString(stock.resolve("manifest.json"), """
                {"version":"1.78.1","minecraft":{"version":"1.21.1"},"files":[{"projectID":1,"fileID":2}]}
                """);
        Files.writeString(publisher.resolve("minecraftinstance.json"), """
                {"manifest":{"version":"1.78.1","minecraft":{"version":"1.21.1"}},
                 "installedAddons":[{"addonID":1,"fileNameOnDisk":"stock.jar","categorySection":{"path":"mods"},
                 "installedFile":{"id":2,"hashes":[{"type":1,"value":"a9993e364706816aba3e25717850c26c9cd0d89d"}]}}]}
                """);
        Path friend = Files.createDirectory(root.resolve("friend"));
        Path friendMods = Files.createDirectory(friend.resolve("mods"));
        Path friendWork = Files.createDirectory(friend.resolve(".neoforgesync"));
        Files.writeString(friendMods.resolve("personal.jar"), "mine");
        Files.writeString(friend.resolve("minecraftinstance.json"), "{\"manifest\":{\"version\":\"1.78.1\"}}");
        var releases = new FakeReleases();

        assertEquals(Publisher.Result.PUBLISHED, Publisher.run(publisher, publisherWork, null, releases, releases));
        var first = assertInstanceOf(Receiver.Ready.class, Receiver.prepare(friend, friendWork, releases, releases));
        runHelper(friend, first.staging());
        assertEquals("xyz", Files.readString(friendMods.resolve("extra.jar")));
        assertEquals("mine", Files.readString(friendMods.resolve("personal.jar")));
        assertInstanceOf(Receiver.UpToDate.class, Receiver.prepare(friend, friendWork, releases, releases));
        assertEquals(Publisher.Result.UNCHANGED, Publisher.run(publisher, publisherWork, null, releases, releases));
        assertEquals(1, releases.releases);

        Files.delete(publisherMods.resolve("extra.jar"));
        assertEquals(Publisher.Result.PUBLISHED, Publisher.run(publisher, publisherWork, null, releases, releases));
        var second = assertInstanceOf(Receiver.Ready.class, Receiver.prepare(friend, friendWork, releases, releases));
        runHelper(friend, second.staging());
        assertFalse(Files.exists(friendMods.resolve("extra.jar")));
        assertEquals("mine", Files.readString(friendMods.resolve("personal.jar")));
        assertEquals(Map.of(), ManagedState.load(friendWork.resolve("managed.json")));
    }

    private void runHelper(Path instance, Path stage) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        String main = Path.of(PostExitHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        String gson = Path.of(com.google.gson.JsonParser.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        long pid = ProcessHandle.current().pid();
        long differentStart = ProcessHandle.current().info().startInstant().orElseThrow().toEpochMilli() + 1;
        Path log = root.resolve("helper-" + stage.getFileName() + ".log");
        Process helper = new ProcessBuilder(java, "-cp", main + System.getProperty("path.separator") + gson,
                PostExitHelper.class.getName(), instance.toString(), stage.getFileName().toString(), Long.toString(pid), Long.toString(differentStart))
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        assertTrue(helper.waitFor(20, TimeUnit.SECONDS));
        assertEquals(0, helper.exitValue(), Files.readString(log));
    }
}
