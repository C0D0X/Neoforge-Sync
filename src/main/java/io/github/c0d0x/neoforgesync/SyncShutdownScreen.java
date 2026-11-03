package io.github.c0d0x.neoforgesync;

import java.io.IOException;
import java.nio.file.Path;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineLabel;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Gives the player a short notice before starting the post-exit helper. */
final class SyncShutdownScreen extends Screen {
    private final Path instance;
    private final Screen previous;
    private final String stage;
    private final String message;
    private MultiLineLabel label = MultiLineLabel.EMPTY;
    private int ticks = 100;

    SyncShutdownScreen(Path instance, Screen previous, String stage, String message) {
        super(Component.literal("NeoForge Sync"));
        this.instance = instance;
        this.previous = previous;
        this.stage = stage;
        this.message = message;
    }

    @Override protected void init() {
        label = MultiLineLabel.create(font, Component.literal(message), Math.max(100, width - 50));
        addRenderableWidget(Button.builder(Component.literal("Not now"), button -> minecraft.setScreen(previous))
                .bounds(width / 2 - 75, height / 2 + 35, 150, 20).build());
    }

    @Override public void tick() {
        if (--ticks > 0) return;
        try {
            HelperLauncher.launch(instance, stage);
            minecraft.stop();
        } catch (IOException e) {
            minecraft.setScreen(new ConfirmScreen(ignored -> minecraft.setScreen(previous), Component.literal("NeoForge Sync"),
                    Component.literal("Could not start the sync helper. No mods were changed. " + e.getMessage()),
                    Component.literal("Continue"), Component.literal("Continue")));
        }
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(font, title, width / 2, height / 2 - 50, 0xffffff);
        label.renderCentered(graphics, width / 2, height / 2 - 30);
    }

    @Override public boolean shouldCloseOnEsc() { return false; }
}
