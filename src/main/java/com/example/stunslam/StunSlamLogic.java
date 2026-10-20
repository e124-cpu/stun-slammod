package com.example.stunslam;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * Logic:
 *  - Looking at a player who is blocking with a shield -> swap to axe and hit (disables the shield).
 *  - Falling (> 1.5 blocks) while looking at a player whose shield is down -> swap to mace and smash.
 * Together this produces the axe -> mace "stun slam" combo.
 */
public final class StunSlamLogic {
    private static final float MIN_FALL = 1.5f;      // mace smash threshold
    private static final float MIN_STRENGTH = 0.95f; // attack cooldown required before swinging

    private StunSlamLogic() {}

    public static void tick(Minecraft mc) {
        LocalPlayer me = mc.player;
        if (me == null || mc.level == null || mc.gameMode == null || mc.screen != null) return;

        HitResult hit = mc.hitResult;
        if (!(hit instanceof EntityHitResult ehr) || !(ehr.getEntity() instanceof Player target)) return;
        if (target == me || !target.isAlive()) return;

        int axeSlot = findHotbar(me, true);
        int maceSlot = findHotbar(me, false);

        if (target.isBlocking()) {
            // Shield is up: break it with an axe.
            if (axeSlot != -1) {
                select(me, axeSlot);
                attackIfReady(mc, me, target);
            }
        } else if (maceSlot != -1 && me.fallDistance > MIN_FALL) {
            // Shield is down and we're falling: mace smash.
            select(me, maceSlot);
            attackIfReady(mc, me, target);
        }
    }

    private static void select(LocalPlayer me, int slot) {
        if (me.getInventory().getSelectedSlot() != slot) {
            me.getInventory().setSelectedSlot(slot);
        }
    }

    private static void attackIfReady(Minecraft mc, LocalPlayer me, Player target) {
        if (me.getAttackStrengthScale(0.0f) >= MIN_STRENGTH) {
            mc.gameMode.attack(me, target);
            me.swing(InteractionHand.MAIN_HAND);
        }
    }

    private static int findHotbar(LocalPlayer me, boolean axe) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = me.getInventory().getItem(i);
            if (axe ? s.is(ItemTags.AXES) : s.is(Items.MACE)) return i;
        }
        return -1;
    }
}
