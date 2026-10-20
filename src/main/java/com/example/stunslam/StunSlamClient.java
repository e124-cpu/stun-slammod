package com.example.stunslam;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
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
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
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

        ClientTickEvents.START_CLIENT_TICK.register(mc -> {
            while (openGui.consumeClick()) {
                if (mc.screen == null) {
                    mc.setScreen(new StunSlamScreen());
                }
            }
            StunSlamLogic.tick(mc);
        });

        // Fires on every left-click attack. Returning FAIL cancels the normal hit.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) ->
                StunSlamLogic.onAttack(player, hand, entity));
    }

    // ------------------------------------------------------------------
    // Logic: click on a shield -> axe hit -> mace swap -> slam
    // ------------------------------------------------------------------
    static final class StunSlamLogic {
        private static final float MIN_FALL = 1.5f;
        private static final int RETRY_TICKS = 20;

        private static boolean busy = false;     // true while WE are attacking
        private static int pending = 0;          // ticks left to wait for a valid slam
        private static Player pendingTarget = null;

        private StunSlamLogic() {}

        private static void say(LocalPlayer me, String msg) {
            if (debug) {
                me.displayClientMessage(Component.literal("[StunSlam] " + msg), true);
            }
        }

        private static boolean shielded(Player t) {
            return t.isBlocking() || (t.isUsingItem() && t.getUseItem().is(Items.SHIELD));
        }

        private static boolean canSlam(LocalPlayer me) {
            return !requireFall || me.fallDistance > MIN_FALL;
        }

        // ---- called for every attack you make on an entity ----
        static InteractionResult onAttack(Player player, InteractionHand hand, Entity entity) {
            if (busy || !enabled) return InteractionResult.PASS;

            Minecraft mc = Minecraft.getInstance();
            LocalPlayer me = mc.player;
            if (me == null || player != me || mc.gameMode == null) return InteractionResult.PASS;
            if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (!(entity instanceof Player target) || target == me) return InteractionResult.PASS;
            if (!shieldBreak) return InteractionResult.PASS;

            if (!shielded(target)) {
                say(me, "Target not blocking");
                return InteractionResult.PASS;
            }

            int axeSlot = findHotbar(me, true);
            if (axeSlot == -1) {
                say(me, "No axe in hotbar");
                return InteractionResult.PASS;
            }
            int maceSlot = findHotbar(me, false);
            int prevSlot = me.getInventory().getSelectedSlot();

            busy = true;
            try {
                // 1) axe hit
                select(me, axeSlot);
                attack(mc, me, target);

                if (!(stunSlam && maceSlot != -1)) {
                    select(me, prevSlot);
                    say(me, "Axe hit on shield");
                } else {
                    // 2) back to mace
                    select(me, maceSlot);

                    // 3) slam now if falling, otherwise wait for it
                    if (canSlam(me)) {
                        attack(mc, me, target);
                        say(me, "SLAM! (fall " + String.format("%.1f", me.fallDistance) + ")");
                    } else {
                        pendingTarget = target;
                        pending = RETRY_TICKS;
                        say(me, "Axe hit, waiting to fall (" + String.format("%.1f", me.fallDistance) + " blocks)");
                    }
                }
            } finally {
                busy = false;
            }

            // cancel the original click so it doesn't hit the shield with the mace
            return InteractionResult.FAIL;
        }

        // ---- retry loop: slam as soon as we are falling far enough ----
        static void tick(Minecraft mc) {
            LocalPlayer me = mc.player;
            if (me == null || mc.level == null || mc.gameMode == null) {
                pending = 0;
                pendingTarget = null;
                return;
            }
            if (!enabled || mc.screen != null) {
                pending = 0;
                pendingTarget = null;
                return;
            }
            if (pending <= 0) return;

            pending--;
            Player t = pendingTarget;
            if (t == null || !t.isAlive() || me.distanceToSqr(t) > 36.0) {
                pending = 0;
                pendingTarget = null;
                say(me, "Combo cancelled (target gone)");
                return;
            }

            if (canSlam(me)) {
                int maceSlot = findHotbar(me, false);
                if (maceSlot != -1) {
                    busy = true;
                    try {
                        select(me, maceSlot);
                        attack(mc, me, t);
                    } finally {
                        busy = false;
                    }
                    say(me, "SLAM! (fall " + String.format("%.1f", me.fallDistance) + ")");
                }
                pending = 0;
                pendingTarget = null;
            } else if (pending == 0) {
                say(me, "Slam expired, not falling enough");
                pendingTarget = null;
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
