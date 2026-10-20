package com.example.stunslam;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class StunSlamScreen extends Screen {
    private Button toggle;

    public StunSlamScreen() {
        super(Component.literal("StunSlam Helper"));
    }

    @Override
    protected void init() {
        int w = 160, h = 20;
        int x = this.width / 2 - w / 2;
        int y = this.height / 2 - h / 2;
        toggle = Button.builder(label(), b -> {
            StunSlamClient.enabled = !StunSlamClient.enabled;
            b.setMessage(label());
        }).bounds(x, y, w, h).build();
        addRenderableWidget(toggle);

        addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                .bounds(x, y + 28, w, h).build());
    }

    private Component label() {
        return Component.literal("Auto Stunslam / Shield Break: " + (StunSlamClient.enabled ? "ON" : "OFF"));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
        super.render(g, mouseX, mouseY, delta);
        g.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
