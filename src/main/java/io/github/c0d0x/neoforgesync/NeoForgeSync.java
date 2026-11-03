package io.github.c0d0x.neoforgesync;

import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModList;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod(value = NeoForgeSync.MOD_ID, dist = Dist.CLIENT)
public final class NeoForgeSync {
    public static final String MOD_ID = "neoforgesync";
    private static final Logger LOGGER = LogUtils.getLogger();
    private final AtomicReference<Notice> notice = new AtomicReference<>();

    public NeoForgeSync() {
        Path instance = FMLPaths.GAMEDIR.get();
        NeoForge.EVENT_BUS.addListener(this::onClientTick);
        Thread.ofVirtual().name("neoforge-sync-startup").start(() -> runStartup(instance));
    }

    private void runStartup(Path instance) {
        try {
            boolean needsRecovery = Files.exists(instance.resolve(".neoforgesync/pending.json"), java.nio.file.LinkOption.NOFOLLOW_LINKS);
            String ownJar = !needsRecovery ? ownJar(instance) : null;
            var outcome = StartupWorkflow.run(instance, ownJar, GitHubSnapshots::latest, new GitHubDownloads(),
                    () -> new GitHubReleaseGateway(GitHubCredential.load()));
            if (outcome instanceof StartupWorkflow.Recovery) {
                notice.set(new Notice("recover", "A previous sync was interrupted. Minecraft will close to restore your files."));
            } else if (outcome instanceof StartupWorkflow.Receiving receiving) {
                if (receiving.result() instanceof Receiver.Ready ready) {
                    notice.set(new Notice(ready.staging().getFileName().toString(), "Mod changes are ready. Minecraft will close to apply them; open the pack again in CurseForge."));
                } else if (receiving.result() instanceof Receiver.VersionMismatch mismatch) {
                    notice.set(new Notice(null, "Your pack version is " + mismatch.installed() + ". Update TNP Limitless 8 in CurseForge to " + mismatch.expected() + " before syncing."));
                }
            } else if (outcome instanceof StartupWorkflow.Publishing publishing) {
                LOGGER.info("Publisher startup: {}", publishing.result());
            }
        } catch (IOException | RuntimeException e) {
            LOGGER.error("Sync startup failed; no live mod files were changed", e);
            notice.set(new Notice(null, "Sync could not complete. Your existing pack was left unchanged. See logs/latest.log for details."));
        }
    }

    private void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof TitleScreen) || minecraft.getOverlay() != null) return;
        Notice next = notice.getAndSet(null);
        if (next == null) return;
        Screen previous = minecraft.screen;
        if (next.stage() == null) {
            minecraft.setScreen(new ConfirmScreen(ignored -> minecraft.setScreen(previous), Component.literal("NeoForge Sync"),
                    Component.literal(next.message()), Component.literal("Continue"), Component.literal("Continue")));
        } else {
            minecraft.setScreen(new SyncShutdownScreen(instancePath(), previous, next.stage(), next.message()));
        }
    }

    private static Path instancePath() { return FMLPaths.GAMEDIR.get(); }

    private static String ownJar(Path instance) throws IOException {
        try {
            var modFile = ModList.get().getModFileById(MOD_ID);
            if (modFile == null) throw new IOException("Cannot identify syncer JAR");
            Path jar = modFile.getFile().getFilePath();
            if (!Files.isRegularFile(jar) || !Files.isSameFile(jar.getParent(), instance.resolve("mods"))) throw new IOException("Syncer is not loaded from the mods directory");
            return jar.getFileName().toString();
        } catch (SecurityException | NullPointerException e) {
            throw new IOException("Cannot identify syncer JAR", e);
        }
    }

    private record Notice(String stage, String message) {}
}
