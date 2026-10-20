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
    // Logic: click on a shield -> axe hit -> mace swap -> slam, ALL in one tick
    // ------------------------------------------------------------------
    static final class StunSlamLogic {
        private static final float MIN_FALL = 1.5f;
        private static final int MAX_WAIT = 20;   // ticks to wait for a valid fall

        private static boolean busy = false;      // true while WE are attacking
        private static int waitTicks = 0;         // >0: holding mace, waiting to fall far enough
        private static Player waitTarget = null;
        private static int waitPrevSlot = -1;
        private static int restoreSlot = -1;      // slot to go back to on the next tick

        private StunSlamLogic() {}

        private static void log(String s) {
            System.out.println("[StunSlam] " + s);
        }

        private static void clear() {
            busy = false;
            waitTicks = 0;
            waitTarget = null;
            waitPrevSlot = -1;
            restoreSlot = -1;
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
            if (!(entity instanceof Player t) || t == me) return InteractionResult.PASS;
            if (!shieldBreak) return InteractionResult.PASS;
            if (!shielded(t)) return InteractionResult.PASS;

            int axeSlot = findHotbar(me, true);
            if (axeSlot == -1) return InteractionResult.PASS;
            int maceSlot = findHotbar(me, false);
            int prev = me.getInventory().getSelectedSlot();
            boolean falling = canSlam(me);

            log("click on shield: fall=" + String.format("%.2f", me.fallDistance)
                    + " axeSlot=" + axeSlot + " maceSlot=" + maceSlot + " prev=" + prev
                    + " falling=" + falling);

            busy = true;
            try {
                // 1) axe hit breaks the shield
                select(me, axeSlot);
                attack(mc, me, t);

                if (stunSlam && maceSlot != -1) {
                    // 2) swap to the mace
                    select(me, maceSlot);

                    if (falling) {
                        // 3) slam in the same tick
                        attack(mc, me, t);
                        restoreSlot = prev;
                        log("slam sent");
                    } else {
                        // not falling yet: hold the mace and slam as soon as we are
                        waitTarget = t;
                        waitPrevSlot = prev;
                        waitTicks = MAX_WAIT;
                        log("axe hit, waiting to fall");
                    }
                } else {
                    select(me, prev);
                }
            } finally {
                busy = false;
            }

            // cancel the original click so it doesn't hit the shield with the mace
            return InteractionResult.FAIL;
        }

        // ---- per-tick: restore slot, or slam once we are falling far enough ----
        static void tick(Minecraft mc) {
            LocalPlayer me = mc.player;
            if (me == null || mc.level == null || mc.gameMode == null || !enabled || mc.screen != null) {
                clear();
                return;
            }

            if (restoreSlot != -1) {
                select(me, restoreSlot);
                restoreSlot = -1;
            }

            if (waitTicks <= 0) return;

            waitTicks--;
            Player t = waitTarget;
            if (t == null || !t.isAlive() || me.distanceToSqr(t) > 36.0) {
                waitTicks = 0;
                waitTarget = null;
                log("wait cancelled (target gone)");
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
                    log("delayed slam sent, fall=" + String.format("%.2f", me.fallDistance));
                    restoreSlot = waitPrevSlot;
                }
                waitTicks = 0;
                waitTarget = null;
            } else if (waitTicks == 0) {
                log("wait expired, never fell far enough");
                waitTarget = null;
            }
        }

        private static void select(LocalPlayer me, int slot) {
            if (slot >= 0 && me.getInventory().getSelectedSlot() != slot) {
                me.getInventory().setSelectedSlot(slot);
            }
        }

        private static void attack(Minecraft mc, LocalPlayer me, Player t) {
            mc.gameMode.attack(me, t);
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
                    Component.literal("Click a shield: axe, then mace"),
                    cx, y + 32, 0xFF9A9AB0);
        }

        @Override
        public boolean isPauseScreen() {
            return false;
        }
    }
}
