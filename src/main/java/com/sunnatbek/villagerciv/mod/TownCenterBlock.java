package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.sim.Policy;
import com.sunnatbek.villagerciv.sim.Settlement;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The physical heart of a settlement and its first storage point. The block holds no data of its
 * own: it is a window onto the settlement record anchored at its position. Goods handed to it go
 * straight into the stockpile ledger, so there is never a second copy to keep in sync.
 */
final class TownCenterBlock extends Block {
	TownCenterBlock(Properties properties) {
		super(properties);
	}

	@Override
	protected InteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
			InteractionHand hand, BlockHitResult hit) {
		Policy plan = ModContent.planOf(stack);
		if (plan != null) {
			if (player instanceof ServerPlayer serverPlayer) {
				EmbodimentController embodiment = VillagerCivMod.embodiment();
				Settlement settlement = embodiment == null ? null : embodiment.settlementAt(pos);
				if (settlement == null) {
					return InteractionResult.PASS;
				}
				embodiment.proposePlan(serverPlayer, settlement, stack, plan);
			}
			return InteractionResult.SUCCESS;
		}
		if (stack.is(ModContent.EXPANSION_CHARTER)) {
			if (player instanceof ServerPlayer serverPlayer) {
				EmbodimentController embodiment = VillagerCivMod.embodiment();
				Settlement settlement = embodiment == null ? null : embodiment.settlementAt(pos);
				FoundingHandler founding = VillagerCivMod.founding();
				if (settlement == null || founding == null) {
					return InteractionResult.PASS;
				}
				founding.proposeExpansion(serverPlayer, settlement, stack);
			}
			return InteractionResult.SUCCESS;
		}
		CommodityValues.Value value = CommodityValues.of(stack);
		if (value == null) {
			// Not something a stockpile wants: fall through to opening the settlement screen.
			return InteractionResult.TRY_WITH_EMPTY_HAND;
		}
		if (player instanceof ServerPlayer serverPlayer) {
			EmbodimentController embodiment = VillagerCivMod.embodiment();
			Settlement settlement = embodiment == null ? null : embodiment.settlementAt(pos);
			if (settlement == null) {
				return InteractionResult.PASS;
			}
			// The whole stack in hand is handed over. (Sneaking cannot mean "all" here: a sneaking
			// player bypasses block interaction and would place the block instead.)
			int items = stack.getCount();
			stack.consume(items, player);
			if (value.tool() != null) {
				embodiment.contributeTools(serverPlayer, settlement, value.tool(), items);
			} else {
				embodiment.contribute(serverPlayer, settlement, value.commodity(), value.unitsPerItem() * items);
			}
		}
		return InteractionResult.SUCCESS;
	}

	@Override
	protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
		if (player instanceof ServerPlayer serverPlayer) {
			EmbodimentController embodiment = VillagerCivMod.embodiment();
			Settlement settlement = embodiment == null ? null : embodiment.settlementAt(pos);
			if (settlement == null) {
				return InteractionResult.PASS;
			}
			boolean own = settlement.founder().equals(serverPlayer.getUUID());
			// Sneaking is how followers are told "this is home": they move in.
			if (own && player.isShiftKeyDown()) {
				embodiment.settleIn(serverPlayer, settlement);
				return InteractionResult.SUCCESS;
			}
			embodiment.showSettlement(serverPlayer, settlement);
			if (own && embodiment.hasFollowers(serverPlayer)) {
				serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.translatable("message.villagerciv.settle.hint"));
			}
		}
		return InteractionResult.SUCCESS;
	}
}
