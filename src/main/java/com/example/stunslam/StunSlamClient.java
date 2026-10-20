package com.example.stunslam;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import org.lwjgl.glfw.GLFW;

public class StunSlamClient implements ClientModInitializer {
    public static boolean enabled = false;
    public static boolean shieldBreak = true;
    public static boolean stunSlam = true;
    public static boolean requireFall = true;

    private static KeyMapping openGui;

    @Override
    public void onInitializeClient() {
        openGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.stunslam.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_KP_5,
                KeyMapping.Category.MISC));

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (openGui.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new StunSlamScreen());
                }
            }
            if (enabled) {
                StunSlamLogic.tick(mc);
            } else {
                StunSlamLogic.reset();
            }
        });
    }

    // ------------------------------------------------------------------
    // Logic: axe breaks the shield, then mace smashes
    // ------------------------------------------------------------------
    static final class StunSlamLogic {
        private static final float MIN_FALL = 1.5f;
        private static final float AXE_READY = 0.95f;
        private static final int MACE_WINDOW = 40;

        private static int maceWindow = 0;

        private StunSlamLogic() {}

        static void reset() {
            maceWindow = 0;
        }

        static void tick(Minecraft mc) {
            LocalPlayer me = mc.player;
            if (me == null || mc.level == null || mc.gameMode == null) {
                maceWindow = 0;
                return;
            }
            if (mc.screen != null) return;

            if (maceWindow > 0) maceWindow--;

            HitResult hit = mc.hitResult;
            if (!(hit instanceof EntityHitResult ehr)) return;
            if (!(ehr.getEntity() instanceof Player target)) return;
            if (target == me || !target.isAlive()) return;

            int axeSlot = findHotbar(me, true);
            int maceSlot = findHotbar(me, false);

            // Step 2: mace follow-up after the axe hit
            if (maceWindow > 0 && maceSlot != -1) {
                select(me, maceSlot);
                if (!requireFall || me.fallDistance > MIN_FALL) {
                    attack(mc, me, target);
                    maceWindow = 0;
                }
                return;
            }

            // Step 1: shield is up -> axe it
            if (shieldBreak && target.isBlocking() && axeSlot != -1) {
                select(me, axeSlot);
                if (me.getAttackStrengthScale(0.0f) >= AXE_READY) {
                    attack(mc, me, target);
                    if (stunSlam && maceSlot != -1) {
                        maceWindow = MACE_WINDOW;
                    }
                }
            }
        }

        private static void select(LocalPlayer me, int slot) {
            if (me.getInventory().getSelectedSlot() != slot) {
                me.getInventory().setSelectedSlot(slot);
            }
        }

        private static void attack(Minecraft mc, LocalPlayer me, Player target) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
        }

        private static int findHotbar(LocalPlayer me, boolean axe) {
            for (int i = 0; i < 9; i++) {
                ItemStack s = me.getInventory().getItem(i);
                if (axe ? s.is(ItemTags.AXES) : s.is(Items.MACE)) return i;
            }
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // GUI
    // ------------------------------------------------------------------
    static class StunSlamScreen extends Screen {
        private static final int PW = 230, PH = 180;

        StunSlamScreen() {
            super(Component.literal("StunSlam Helper"));
        }

        private int px() { return this.width / 2 - PW / 2; }
        private int py() { return this.height / 2 - PH / 2; }

        private static Component state(String name, boolean on) {
            return Component.literal(name + ": ")
                    .append(Component.literal(on ? "ON" : "OFF")
                            .withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
        }

        @Override
        protected void init() {
            int left = px() + 15;
            int top = py();

            addRenderableWidget(Button.builder(state("Automation", enabled), b -> {
                enabled = !enabled;
                b.setMessage(state("Automation", enabled));
            }).bounds(left, top + 42, 200, 24).build());

            addRenderableWidget(Button.builder(state("Shield Break", shieldBreak), b -> {
                shieldBreak = !shieldBreak;
                b.setMessage(state("Shield Break", shieldBreak));
            }).bounds(left, top + 74, 200, 20).build());

            addRenderableWidget(Button.builder(state("Stun Slam (mace follow-up)", stunSlam), b -> {
                stunSlam = !stunSlam;
                b.setMessage(state("Stun Slam (mace follow-up)", stunSlam));
            }).bounds(left, top + 98, 200, 20).build());

            addRenderableWidget(Button.builder(state("Require Falling", requireFall), b -> {
                requireFall = !requireFall;
                b.setMessage(state("Require Falling", requireFall));
            }).bounds(left, top + 122, 200, 20).build());

            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                    .bounds(left, top + 150, 200, 20).build());
        }

        @Override
        public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float delta) {
            super.renderBackground(g, mouseX, mouseY, delta);
            int x = px(), y = py();
            g.fill(x - 1, y - 1, x + PW + 1, y + PH + 1, 0xFF7C5CFF);
            g.fill(x, y, x + PW, y + PH, 0xF0101018);
            g.fill(x, y, x + PW, y + 28, 0xFF1E1B3A);
            g.fill(x, y + 28, x + PW, y + 29, 0xFF7C5CFF);
        }

        @Override
        public void render(GuiGraphics g, int mouseX, int mouseY, float delta) {
            super.render(g, mouseX, mouseY, delta);
            int cx = this.width / 2;
            int y = py();
            g.drawCenteredString(this.font,
                    Component.literal("StunSlam Helper").withStyle(ChatFormatting.BOLD),
                    cx, y + 10, 0xFFFFFFFF);
            g.drawCenteredString(this.font,
                    Component.literal("Axe breaks shield, then mace smashes"),
                    cx, y + 32, 0xFF9A9AB0);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
