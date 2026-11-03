package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Separate JVM entry point; never changes mods until the specified game process has exited. */
public final class PostExitHelper {
    private PostExitHelper() {}

    public static void main(String[] args) {
        try {
            if (args.length != 4 && args.length != 5) throw new IOException("Expected instance, stage, game PID, game start time and optional updater name");
            String ownJar = args.length == 5 && !args[4].equals("none") ? args[4] : null;
            apply(Path.of(args[0]), args[1], Long.parseLong(args[2]), Long.parseLong(args[3]), ownJar);
            System.out.println("Sync applied. Start the pack again in CurseForge.");
        } catch (Exception e) {
            System.err.println("Sync was not completed: " + e.getMessage());
            System.exit(1);
        }
    }

    static void apply(Path instance, String stageName, long pid, long startMillis) throws IOException {
        apply(instance, stageName, pid, startMillis, null);
    }

    static void apply(Path instance, String stageName, long pid, long startMillis, String ownJar) throws IOException {
        if (pid <= 0 || startMillis <= 0 || (!stageName.equals("recover") && !stageName.matches("stage-[A-Za-z0-9_-]{1,100}"))) throw new IOException("Invalid helper arguments");
        Path work = instance.resolve(".neoforgesync");
        if (Files.isSymbolicLink(work) || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid helper directory");
        if (stageName.equals("recover")) {
            waitForGame(pid, startMillis);
            RecoverableApply.recover(instance.resolve("mods"), work);
            return;
        }
        Path stage = work.resolve(stageName);
        if (Files.isSymbolicLink(stage) || !Files.isDirectory(stage, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid helper directories");
        Path manifestPath = stage.resolve("manifest.json");
        if (Files.isSymbolicLink(manifestPath) || !Files.isRegularFile(manifestPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Staged manifest missing");
        SyncManifest manifest;
        try (var input = Files.newInputStream(manifestPath)) {
            byte[] bytes = input.readNBytes(2 * 1024 * 1024 + 1);
            if (bytes.length > 2 * 1024 * 1024) throw new IOException("Staged manifest too large");
            manifest = SyncManifest.parse(new String(bytes, StandardCharsets.UTF_8));
        }
        waitForGame(pid, startMillis);
        if (!manifest.packVersion().equals(Receiver.packVersion(instance.resolve("minecraftinstance.json")))) {
            throw new IOException("Pack version changed before application");
        }
        RecoverableApply.recover(instance.resolve("mods"), work);
        if (manifest.updater() != null) {
            SelfUpdate.effective(manifest, ownJar);
            try {
                Path helperJar = Path.of(PostExitHelper.class.getProtectionDomain().getCodeSource().getLocation().toURI());
                Path original = instance.resolve("mods").resolve(ownJar);
                if (!Files.isRegularFile(helperJar, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(original)
                        || !Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS) || Files.mismatch(helperJar, original) != -1) {
                    throw new IOException("Installed updater no longer matches the running helper");
                }
            } catch (java.net.URISyntaxException | NullPointerException e) {
                throw new IOException("Cannot verify running updater", e);
            }
        }
        RecoverableApply.apply(instance.resolve("mods"), stage, work, manifest, ownJar);
    }

    private static void waitForGame(long pid, long startMillis) throws IOException {
        var process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.get().isAlive()) return;
        Instant started = process.get().info().startInstant().orElseThrow(() -> new IOException("Cannot identify the game process"));
        if (started.toEpochMilli() != startMillis) return;
        try {
            process.get().onExit().get(30, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Waiting for the game was interrupted", e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IOException("Game did not exit", e);
        }
    }
}
