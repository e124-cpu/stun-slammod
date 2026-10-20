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
