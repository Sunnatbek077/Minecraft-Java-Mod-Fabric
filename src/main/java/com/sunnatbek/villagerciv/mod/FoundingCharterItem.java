package com.sunnatbek.villagerciv.mod;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;

/** Used on a block to propose that spot for the Town Center of a new settlement. */
final class FoundingCharterItem extends Item {
	FoundingCharterItem(Properties properties) {
		super(properties);
	}

	@Override
	public InteractionResult useOn(UseOnContext context) {
		if (context.getPlayer() instanceof ServerPlayer player) {
			FoundingHandler handler = VillagerCivMod.founding();
			if (handler != null) {
				handler.propose(player, context.getClickedPos(), context.getClickedFace());
			}
		}
		return InteractionResult.SUCCESS;
	}
}
