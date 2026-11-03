package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SyncPlanTest {
    private SyncManifest manifest(String name, String hash) {
        return new SyncManifest("1", List.of(new SyncManifest.ModFile(name, hash, 3, URI.create("https://github.com/a"))));
    }

    @Test void addsReplacesAndRemovesManagedModsOnly() throws Exception {
        var plan = SyncPlan.calculate(Map.of("old.jar", "old", "change.jar", "one"),
                Map.of("old.jar", "old", "change.jar", "one", "personal.jar", "personal"), manifest("change.jar", "two"));
        assertEquals(Set.of("change.jar"), plan.install());
        assertEquals(Set.of("old.jar"), plan.remove());
    }

    @Test void refusesToOverwritePersonalFile() {
        assertThrows(IOException.class, () -> SyncPlan.calculate(Map.of(), Map.of("mod.jar", "mine"), manifest("mod.jar", "theirs")));
    }

    @Test void refusesToRemoveLocallyModifiedManagedFile() {
        assertThrows(IOException.class, () -> SyncPlan.calculate(Map.of("old.jar", "before"), Map.of("old.jar", "edited"), manifest("new.jar", "new")));
    }

    @Test void refusesCaseInsensitiveCollisions() {
        assertThrows(IOException.class, () -> SyncPlan.calculate(Map.of(), Map.of("MOD.jar", "mine"), manifest("mod.jar", "theirs")));
    }
}
