package io.github.c0d0x.neoforgesync;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class StartupWorkflowTest {
    @TempDir Path root;

    @Test void recoveryTakesPrecedenceOverNetworkingAndPublishing() throws Exception {
        Path work = Files.createDirectory(root.resolve(".neoforgesync"));
        Files.writeString(work.resolve("pending.json"), "pending");
        Files.createDirectories(root.resolve("stock"));
        Files.writeString(root.resolve("stock/manifest.json"), "{}");
        var result = StartupWorkflow.run(root, "syncer.jar", () -> { throw new AssertionError("network called"); },
                uri -> { throw new AssertionError("download called"); }, () -> { throw new AssertionError("credential requested"); });
        assertInstanceOf(StartupWorkflow.Recovery.class, result);
    }

    @Test void receiverWithoutSnapshotDoesNotRequestCredentials() throws Exception {
        var result = StartupWorkflow.run(root, null, Optional::empty,
                uri -> { throw new AssertionError("download called"); }, () -> { throw new AssertionError("credential requested"); });
        assertInstanceOf(Receiver.NoSnapshot.class, assertInstanceOf(StartupWorkflow.Receiving.class, result).result());
        assertTrue(Files.isDirectory(root.resolve(".neoforgesync")));
    }
}
