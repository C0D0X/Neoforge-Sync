package io.github.c0d0x.neoforgesync;

import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Journaled post-exit application. Recovery rolls an interrupted transaction back. */
public final class RecoverableApply {
    private RecoverableApply() {}
    @FunctionalInterface interface Checkpoint { void reached(String phase) throws IOException; }

    public static void apply(Path mods, Path staged, Path work, SyncManifest manifest) throws IOException {
        apply(mods, staged, work, manifest, null, phase -> {});
    }

    static void apply(Path mods, Path staged, Path work, SyncManifest manifest, Checkpoint checkpoint) throws IOException {
        apply(mods, staged, work, manifest, null, checkpoint);
    }

    public static void apply(Path mods, Path staged, Path work, SyncManifest manifest, String ownJar) throws IOException {
        apply(mods, staged, work, manifest, ownJar, phase -> {});
    }

    static void apply(Path mods, Path staged, Path work, SyncManifest manifest, String ownJar, Checkpoint checkpoint) throws IOException {
        withLock(work, () -> { applyLocked(mods, staged, work, manifest, ownJar, checkpoint); return null; });
    }

    private static void applyLocked(Path mods, Path staged, Path work, SyncManifest manifest, String ownJar, Checkpoint checkpoint) throws IOException {
        checkWork(work);
        if (Files.exists(work.resolve("pending.json"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Pending update requires recovery");
        Map<String, String> previous = ManagedState.load(work.resolve("managed.json"));
        Map<String, String> current = ModInventory.scan(mods);
        SyncManifest effective = SelfUpdate.effective(manifest, ownJar);
        Map<String, String> planningPrevious = new TreeMap<>(previous);
        if (manifest.updater() != null) {
            String existing = current.get(ownJar);
            if (existing == null || planningPrevious.containsKey(ownJar)) throw new IOException("Installed updater cannot be adopted");
            planningPrevious.put(ownJar, existing);
        }
        SyncPlan plan = SyncPlan.calculate(planningPrevious, current, effective);
        Map<String, String> desired = new TreeMap<>();
        for (var file : effective.mods()) {
            if (desired.putIfAbsent(file.name(), file.sha256()) != null) throw new IOException("Duplicate desired mod");
        }
        Map<String, String> managedAfter = new TreeMap<>(desired);
        if (manifest.updater() != null) managedAfter.remove(ownJar);
        Map<String, String> stagedHashes = ModInventory.scan(staged);
        for (String name : plan.install()) {
            if (!Objects.equals(desired.get(name), stagedHashes.get(name))) throw new IOException("Staged file mismatch: " + name);
        }
        Set<String> affected = new HashSet<>(plan.install());
        affected.addAll(plan.remove());
        if (affected.isEmpty()) return;
        String backupName = "transaction-" + UUID.randomUUID();
        Path backup = Files.createDirectory(work.resolve(backupName));
        ManagedState.save(backup.resolve("before.json"), previous);
        ManagedState.save(backup.resolve("after.json"), managedAfter);
        JsonObject changes = new JsonObject();
        for (String name : new java.util.TreeSet<>(affected)) {
            validateName(name);
            String oldHash = current.get(name);
            String newHash = plan.install().contains(name) ? desired.get(name) : null;
            JsonObject change = new JsonObject();
            if (oldHash == null) change.add("old", JsonNull.INSTANCE); else change.addProperty("old", oldHash);
            if (newHash == null) change.add("new", JsonNull.INSTANCE); else change.addProperty("new", newHash);
            changes.add(name, change);
            if (oldHash != null) copyDurably(mods.resolve(name), backup.resolve(name));
        }
        Map<String, String> backups = ModInventory.scan(backup);
        for (String name : affected) {
            if (!Objects.equals(current.get(name), backups.get(name))) throw new IOException("Backup verification failed: " + name);
        }
        Map<String, String> checked = ModInventory.scan(mods);
        for (String name : affected) {
            if (!Objects.equals(current.get(name), checked.get(name))) throw new IOException("Mod changed during preparation: " + name);
        }
        JsonObject journal = new JsonObject();
        journal.addProperty("schema", 1);
        journal.addProperty("backup", backupName);
        journal.add("changes", changes);
        writeAtomic(work.resolve("pending.json"), journal.toString().getBytes(StandardCharsets.UTF_8));
        checkpoint.reached("journal");
        for (String name : new java.util.TreeSet<>(plan.install())) {
            replaceFrom(staged.resolve(name), mods.resolve(name));
            checkpoint.reached("install:" + name);
        }
        for (String name : new java.util.TreeSet<>(plan.remove())) {
            Files.delete(mods.resolve(name));
            checkpoint.reached("remove:" + name);
        }
        Map<String, String> installed = ModInventory.scan(mods);
        for (String name : plan.install()) {
            if (!Objects.equals(desired.get(name), installed.get(name))) throw new IOException("Installed mod hash mismatch: " + name);
        }
        for (String name : plan.remove()) {
            if (installed.containsKey(name)) throw new IOException("Removed mod is still present: " + name);
        }
        ManagedState.save(work.resolve("managed.json"), managedAfter);
        checkpoint.reached("state");
        Files.delete(work.resolve("pending.json"));
    }

    public static boolean recover(Path mods, Path work) throws IOException {
        return withLock(work, () -> recoverLocked(mods, work));
    }

    private static boolean recoverLocked(Path mods, Path work) throws IOException {
        checkWork(work);
        Path journalPath = work.resolve("pending.json");
        if (!Files.exists(journalPath, LinkOption.NOFOLLOW_LINKS)) return false;
        if (Files.isSymbolicLink(journalPath) || !Files.isRegularFile(journalPath, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid pending journal");
        JsonObject journal;
        try (var input = Files.newInputStream(journalPath)) {
            byte[] bytes = input.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Pending journal too large");
            journal = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (com.google.gson.JsonParseException | IllegalStateException e) {
            throw new IOException("Invalid pending journal", e);
        }
        try {
            if (journal.get("schema").getAsInt() != 1) throw new IOException("Invalid pending schema");
            String backupName = journal.get("backup").getAsString();
            if (!backupName.matches("transaction-[a-f0-9-]{36}")) throw new IOException("Invalid backup name");
            Path backup = work.resolve(backupName);
            checkWork(backup);
            Map<String, String> before = ManagedState.load(backup.resolve("before.json"));
            if (!Files.isRegularFile(backup.resolve("before.json"), LinkOption.NOFOLLOW_LINKS)) throw new IOException("Missing prior state");
            Map<String, String> backups = ModInventory.scan(backup);
            Map<String, String> current = ModInventory.scan(mods);
            JsonObject changes = journal.getAsJsonObject("changes");
            if (changes.size() > 2000) throw new IOException("Too many journal entries");
            Map<String, String> restore = new TreeMap<>();
            for (var item : changes.entrySet()) {
                String name = item.getKey();
                validateName(name);
                JsonObject change = item.getValue().getAsJsonObject();
                String oldHash = optionalHash(change, "old"), newHash = optionalHash(change, "new");
                if (oldHash == null && newHash == null) throw new IOException("Empty journal entry");
                if (!Objects.equals(oldHash, backups.get(name))) throw new IOException("Backup verification failed: " + name);
                String now = current.get(name);
                if (now != null && !now.equals(oldHash) && !now.equals(newHash)) throw new IOException("Mod changed after interruption: " + name);
                restore.put(name, oldHash);
            }
            // Recovery is repeatable: the journal remains until every file and prior state are restored.
            for (var entry : restore.entrySet()) {
                if (entry.getValue() == null) Files.deleteIfExists(mods.resolve(entry.getKey()));
                else replaceFrom(backup.resolve(entry.getKey()), mods.resolve(entry.getKey()));
            }
            ManagedState.save(work.resolve("managed.json"), before);
            Files.delete(journalPath);
            return true;
        } catch (IllegalStateException | NullPointerException | ClassCastException e) {
            throw new IOException("Invalid pending journal", e);
        }
    }

    private static String optionalHash(JsonObject value, String key) throws IOException {
        var entry = value.get(key);
        if (entry == null) throw new IOException("Missing journal hash");
        if (entry.isJsonNull()) return null;
        if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString() || !entry.getAsString().matches("[a-f0-9]{64}")) throw new IOException("Invalid journal hash");
        return entry.getAsString();
    }

    private static void checkWork(Path work) throws IOException {
        if (Files.isSymbolicLink(work) || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid transaction directory");
    }

    @FunctionalInterface private interface Locked<T> { T run() throws IOException; }

    private static <T> T withLock(Path work, Locked<T> action) throws IOException {
        checkWork(work);
        Path path = work.resolve("apply.lock");
        if (Files.isSymbolicLink(path)) throw new IOException("Transaction lock is a symbolic link");
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Another sync transaction is running");
                return action.run();
            } catch (java.nio.channels.OverlappingFileLockException e) {
                throw new IOException("Another sync transaction is running", e);
            }
        }
    }

    private static void validateName(String name) throws IOException {
        if (name == null || !name.matches("[A-Za-z0-9_+.-]{1,240}\\.jar") || name.contains("..")) throw new IOException("Invalid transaction filename");
    }

    private static void copyDurably(Path source, Path target) throws IOException {
        Files.copy(source, target);
        try (FileChannel channel = FileChannel.open(target, StandardOpenOption.WRITE)) { channel.force(true); }
    }

    private static void replaceFrom(Path source, Path target) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "sync-", ".tmp");
        try {
            Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temp); }
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path temp = Files.createTempFile(target.getParent(), "journal-", ".tmp");
        try {
            Files.write(temp, bytes);
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temp); }
    }
}
