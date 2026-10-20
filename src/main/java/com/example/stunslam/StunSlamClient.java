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
    public static boolean debug = true;

    private static KeyMapping openGui;

    @Override
    public void onInitializeClient() {
        openGui = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.stunslam.open_gui",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_KP_5,
                KeyMapping.Category.MISC));

        // START of tick so we can intercept your click before vanilla attacks
        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (openGui.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new StunSlamScreen());
                }
            }
            StunSlamLogic.tick(mc);
        });
    }

    // ------------------------------------------------------------------
    // Logic: click on a shield -> axe hit -> back to mace -> slam
    // ------------------------------------------------------------------
    static final class StunSlamLogic {
        private static final float MIN_FALL = 1.5f;
        private static final int GAP = 1; // ticks between steps

        private static boolean wasDown = false;
        private static int step = 0;       // 0 idle, 1 swap to mace, 2 mace attack
        private static int wait = 0;
        private static Player comboTarget = null;

        private StunSlamLogic() {}

        private static void say(LocalPlayer me, String msg) {
            if (debug) {
                me.displayClientMessage(Component.literal("[StunSlam] " + msg), true);
            }
        }

        private static boolean shielded(Player t) {
            return t.isUsingItem() && t.getUseItem().is(Items.SHIELD);
        }

        static void tick(Minecraft mc) {
            LocalPlayer me = mc.player;
            if (me == null || mc.level == null || mc.gameMode == null) {
                step = 0;
                wasDown = false;
                return;
            }

            boolean down = mc.options.keyAttack.isDown();
            boolean pressed = down && !wasDown;
            wasDown = down;

            if (!enabled || mc.screen != null) {
                step = 0;
                return;
            }

            // ---- follow-up steps (swap back to mace, then slam) ----
            if (step != 0) {
                if (wait > 0) {
                    wait--;
                    return;
                }
                if (step == 1) {
                    int maceSlot = findHotbar(me, false);
                    if (maceSlot == -1) {
                        say(me, "No mace in hotbar");
                        step = 0;
                        return;
                    }
                    select(me, maceSlot);
                    say(me, "Swapped to mace");
                    step = 2;
                    wait = GAP;
                    return;
                }
                if (step == 2) {
                    step = 0;
                    Player t = comboTarget;
                    comboTarget = null;
                    if (t == null || !t.isAlive()) {
                        say(me, "Target gone");
                        return;
                    }
                    if (requireFall && me.fallDistance <= MIN_FALL) {
                        say(me, "Not falling enough (" + String.format("%.1f", me.fallDistance) + " blocks)");
                        return;
                    }
                    if (me.distanceToSqr(t) > 36.0) {
                        say(me, "Target out of reach");
                        return;
                    }
                    attack(mc, me, t);
                    say(me, "SLAM! (fall " + String.format("%.1f", me.fallDistance) + ")");
                    return;
                }
            }

            // ---- trigger: you clicked while looking at a shielded player ----
            if (!pressed) return;

            HitResult hit = mc.hitResult;
            if (!(hit instanceof EntityHitResult ehr)) return;
            if (!(ehr.getEntity() instanceof Player target)) return;
            if (target == me || !target.isAlive() || !shielded(target)) return;
            if (!shieldBreak) return;

            int axeSlot = findHotbar(me, true);
            if (axeSlot == -1) {
                say(me, "No axe in hotbar");
                return;
            }
            int maceSlot = findHotbar(me, false);

            // eat your click so the mace doesn't hit the shield
            while (mc.options.keyAttack.consumeClick()) { }

            int prevSlot = me.getInventory().getSelectedSlot();
            select(me, axeSlot);
            attack(mc, me, target);
            say(me, "Axe hit on shield");

            if (stunSlam && maceSlot != -1) {
                comboTarget = target;
                step = 1;
                wait = GAP;
            } else {
                select(me, prevSlot);
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
        private static final int PW = 230, PH = 206;

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

            addRenderableWidget(Button.builder(state("Shield Break (axe)", shieldBreak), b -> {
                shieldBreak = !shieldBreak;
                b.setMessage(state("Shield Break (axe)", shieldBreak));
            }).bounds(left, top + 74, 200, 20).build());

            addRenderableWidget(Button.builder(state("Stun Slam (mace)", stunSlam), b -> {
                stunSlam = !stunSlam;
                b.setMessage(state("Stun Slam (mace)", stunSlam));
            }).bounds(left, top + 98, 200, 20).build());

            addRenderableWidget(Button.builder(state("Require Falling", requireFall), b -> {
                requireFall = !requireFall;
                b.setMessage(state("Require Falling", requireFall));
            }).bounds(left, top + 122, 200, 20).build());

            addRenderableWidget(Button.builder(state("Debug Messages", debug), b -> {
                debug = !debug;
                b.setMessage(state("Debug Messages", debug));
            }).bounds(left, top + 146, 200, 20).build());

            addRenderableWidget(Button.builder(Component.literal("Close"), b -> onClose())
                    .bounds(left, top + 176, 200, 20).build());
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
                    Component.literal("Click a shield: axe, then mace"),
                    cx, y + 32, 0xFF9A9AB0);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
