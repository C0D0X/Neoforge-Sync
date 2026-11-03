package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Chooses publisher, receiver, or recovery without modifying live mod files. */
public final class StartupWorkflow {
    private StartupWorkflow() {}
    @FunctionalInterface public interface GatewayFactory { ReleasePublisher.Gateway open() throws IOException; }

    public sealed interface Outcome permits Recovery, Publishing, Receiving {}
    public record Recovery() implements Outcome {}
    public record Publishing(Publisher.Result result) implements Outcome {}
    public record Receiving(Receiver.Result result) implements Outcome {}

    public static Outcome run(Path instance, String ownJar, Receiver.Source source,
                              StagedDownloads.Fetcher fetcher, GatewayFactory factory) throws IOException {
        Path work = instance.resolve(".neoforgesync");
        if (Files.isSymbolicLink(work)) throw new IOException("Sync directory is a symbolic link");
        if (!Files.exists(work, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(work);
        if (!Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Sync directory is invalid");
        if (Files.exists(work.resolve("pending.json"), LinkOption.NOFOLLOW_LINKS)) return new Recovery();
        if (Files.isRegularFile(instance.resolve("stock/manifest.json"), LinkOption.NOFOLLOW_LINKS)) {
            if (ownJar == null || ownJar.isBlank()) throw new IOException("Cannot identify the installed syncer JAR");
            return new Publishing(Publisher.run(instance, work, ownJar, source, new LazyGateway(factory)));
        }
        return new Receiving(Receiver.prepare(instance, work, source, fetcher, ownJar));
    }

    private static final class LazyGateway implements ReleasePublisher.Gateway {
        private final GatewayFactory factory;
        private ReleasePublisher.Gateway delegate;
        private LazyGateway(GatewayFactory factory) { this.factory = factory; }
        public long createDraft(String tag) throws IOException { delegate = factory.open(); return delegate.createDraft(tag); }
        public void upload(long releaseId, String name, Path file, String contentType) throws IOException { delegate.upload(releaseId, name, file, contentType); }
        public void publish(long releaseId) throws IOException { delegate.publish(releaseId); }
        public void deleteDraft(long releaseId) throws IOException { delegate.deleteDraft(releaseId); }
    }
}
