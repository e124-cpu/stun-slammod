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
    // Logic: click on a shield -> axe hit -> swap to mace -> slam
    // ------------------------------------------------------------------
    static final class StunSlamLogic {
        private static final float MIN_FALL = 1.5f;
        private static final int AXE_HOLD = 3;    // ticks you see the axe before swapping to mace
        private static final int MACE_HOLD = 2;   // ticks you see the mace before the slam
        private static final int MAX_TICKS = 40;  // combo always gives up after this

        private static boolean busy = false;      // true while WE are attacking
        private static int step = 0;              // 0 idle, 1 swap to mace, 2 slam, 3 restore slot
        private static int wait = 0;
        private static int timeout = 0;
        private static int prevSlot = 0;
        private static Player target = null;

        private StunSlamLogic() {}

        private static void reset() {
            step = 0;
            wait = 0;
            timeout = 0;
            target = null;
            busy = false;
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

            // combo already running: swallow extra clicks so they don't hit with the wrong item
            if (step != 0) return InteractionResult.FAIL;

            if (!shieldBreak) return InteractionResult.PASS;
            if (!shielded(t)) return InteractionResult.PASS;

            int axeSlot = findHotbar(me, true);
            if (axeSlot == -1) return InteractionResult.PASS;
            int maceSlot = findHotbar(me, false);

            prevSlot = me.getInventory().getSelectedSlot();

            // 1) axe hit
            select(me, axeSlot);
            busy = true;
            try {
                attack(mc, me, t);
            } finally {
                busy = false;
            }

            target = t;
            timeout = MAX_TICKS;
            wait = AXE_HOLD;
            step = (stunSlam && maceSlot != -1) ? 1 : 3;

            // cancel the original click so it doesn't hit the shield with the mace
            return InteractionResult.FAIL;
        }

        // ---- steps 2 and 3 run on later ticks so the swaps are visible ----
        static void tick(Minecraft mc) {
            LocalPlayer me = mc.player;
            if (me == null || mc.level == null || mc.gameMode == null || !enabled || mc.screen != null) {
                if (step != 0) reset();
                return;
            }
            if (step == 0) return;

            if (--timeout <= 0) {
                reset();
                return;
            }

            if (wait > 0) {
                wait--;
                return;
            }

            if (step == 3) {
                select(me, prevSlot);
                reset();
                return;
            }

            Player t = target;
            if (t == null || !t.isAlive() || me.distanceToSqr(t) > 36.0) {
                reset();
                return;
            }

            if (step == 1) {
                int maceSlot = findHotbar(me, false);
                if (maceSlot == -1) {
                    reset();
                    return;
                }
                select(me, maceSlot);
                step = 2;
                wait = MACE_HOLD;
                return;
            }

            if (step == 2) {
                if (!canSlam(me)) return; // keep waiting until we are falling far enough
                busy = true;
                try {
                    attack(mc, me, t);
                } finally {
                    busy = false;
                }
                reset();
            }
        }

        private static void select(LocalPlayer me, int slot) {
            if (me.getInventory().getSelectedSlot() != slot) {
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
