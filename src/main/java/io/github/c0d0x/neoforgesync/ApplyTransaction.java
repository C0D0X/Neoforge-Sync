package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** File transaction primitive. Call only after Minecraft has exited. */
public final class ApplyTransaction {
    private ApplyTransaction() {}

    public static void apply(Path mods, Path staged, Path backupParent, Map<String, String> managed,
                             SyncManifest manifest) throws IOException {
        Map<String, String> current = ModInventory.scan(mods);
        SyncPlan plan = SyncPlan.calculate(managed, current, manifest);
        Map<String, String> expected = new HashMap<>();
        for (var file : manifest.mods()) expected.put(file.name(), file.sha256());
        Map<String, String> stagedFiles = ModInventory.scan(staged);
        for (String name : plan.install()) {
            if (!expected.get(name).equals(stagedFiles.get(name))) throw new IOException("Staged file hash mismatch: " + name);
        }
        if (Files.isSymbolicLink(backupParent) || !Files.isDirectory(backupParent, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Backup parent must be an existing directory");
        }
        Set<String> affected = new HashSet<>(plan.install());
        affected.addAll(plan.remove());
        Path backup = Files.createTempDirectory(backupParent, "backup-");
        List<String> touched = new ArrayList<>();
        try {
            for (String name : affected) {
                validateName(name);
                if (current.containsKey(name)) Files.copy(mods.resolve(name), backup.resolve(name));
            }
            // Recheck all affected files before changing anything, including files absent at planning time.
            Map<String, String> checked = ModInventory.scan(mods);
            for (String name : affected) {
                if (!java.util.Objects.equals(current.get(name), checked.get(name))) throw new IOException("Mod changed during preparation: " + name);
            }
            for (String name : plan.install()) {
                touched.add(name);
                Files.move(staged.resolve(name), mods.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            for (String name : plan.remove()) {
                touched.add(name);
                Files.delete(mods.resolve(name));
            }
        } catch (IOException | RuntimeException failure) {
            IOException rollbackFailure = null;
            for (int i = touched.size() - 1; i >= 0; i--) {
                String name = touched.get(i);
                try {
                    Path saved = backup.resolve(name);
                    if (current.containsKey(name)) Files.copy(saved, mods.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    else Files.deleteIfExists(mods.resolve(name));
                } catch (IOException e) {
                    if (rollbackFailure == null) rollbackFailure = e;
                    else rollbackFailure.addSuppressed(e);
                }
            }
            if (rollbackFailure != null) failure.addSuppressed(rollbackFailure);
            throw failure;
        }
        // Backups are intentionally retained for recovery; cleanup belongs to the caller.
    }

    private static void validateName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_+.-]{1,240}\\.jar") || name.contains("..")) {
            throw new IOException("Invalid mod filename");
        }
    }
}
