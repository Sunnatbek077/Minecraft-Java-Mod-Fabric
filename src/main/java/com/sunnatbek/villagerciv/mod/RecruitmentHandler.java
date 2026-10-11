package com.sunnatbek.villagerciv.mod;

import com.sunnatbek.villagerciv.mod.net.RecruitOfferPayload;
import com.sunnatbek.villagerciv.sim.Recruitment;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.Simulation;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * Recruiting a vanilla villager with a Recruitment Scroll. Using the scroll opens the offer
 * screen with the villager's terms; only confirming there actually asks, and the villager may
 * still refuse.
 */
final class RecruitmentHandler {
	private static final double MAX_OFFER_DISTANCE_SQR = 8.0 * 8.0;

	private RecruitmentHandler() {
	}

	/** Scroll used on a villager: show the offer. */
	static InteractionResult onUseEntity(Player player, Level level, InteractionHand hand, Entity entity, EntityHitResult hit) {
		ItemStack stack = player.getItemInHand(hand);
		if (!stack.is(ModContent.RECRUITMENT_SCROLL) || !(entity instanceof Villager villager) || player.isSpectator()) {
			return InteractionResult.PASS;
		}
		if (!(player instanceof ServerPlayer serverPlayer)) {
			return InteractionResult.SUCCESS;
		}
		Simulation simulation = VillagerCivMod.simulation();
		if (simulation == null || level.dimension() != Level.OVERWORLD) {
			return InteractionResult.PASS;
		}
		if (villager.isBaby()) {
			serverPlayer.sendSystemMessage(Component.translatable("message.villagerciv.recruit.child"), true);
			return InteractionResult.SUCCESS;
		}
		String profession = professionOf(villager);
		Recruitment.Offer offer = simulation.previewRecruit(player.getUUID(), villager.getUUID(), profession, !WorkDay.isDay(level));
		ServerPlayNetworking.send(serverPlayer, new RecruitOfferPayload(villager.getId(), profession,
				(int) Math.round(offer.chance() * 100), offer.unemployed(), offer.night(),
				minutes(offer.cooldownTicks()), offer.size(), Recruitment.MAX_EXPEDITION_SIZE));
		return InteractionResult.SUCCESS;
	}

	/** Offer confirmed on the screen: the villager answers. */
	static void confirm(ServerPlayer player, int villagerEntityId) {
		Simulation simulation = VillagerCivMod.simulation();
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		ServerLevel level = player.level();
		// Everything is checked again: the screen may have been open for a while.
		if (simulation == null || embodiment == null || level.dimension() != Level.OVERWORLD
				|| !(level.getEntity(villagerEntityId) instanceof Villager villager) || !villager.isAlive() || villager.isBaby()
				|| player.distanceToSqr(villager) > MAX_OFFER_DISTANCE_SQR) {
			return;
		}
		ItemStack stack = player.getMainHandItem().is(ModContent.RECRUITMENT_SCROLL) ? player.getMainHandItem() : player.getOffhandItem();
		if (!stack.is(ModContent.RECRUITMENT_SCROLL)) {
			return;
		}

		String givenName = villager.hasCustomName() ? villager.getCustomName().getString() : null;
		Recruitment.Result result = simulation.recruit(player.getUUID(), villager.getUUID(), professionOf(villager), givenName,
				!WorkDay.isDay(level), villager.getX(), villager.getY(), villager.getZ());
		switch (result) {
			case Recruitment.Accepted accepted -> {
				stack.consume(1, player);
				feedback(level, villager, ParticleTypes.HAPPY_VILLAGER, SoundEvents.VILLAGER_YES);
				float yRot = villager.getYRot();
				// The vanilla villager leaves its village for good: free its bed and workstation.
				villager.releasePoi(MemoryModuleType.HOME);
				villager.releasePoi(MemoryModuleType.JOB_SITE);
				villager.releasePoi(MemoryModuleType.POTENTIAL_JOB_SITE);
				villager.releasePoi(MemoryModuleType.MEETING_POINT);
				villager.discard();
				if (embodiment.materialize(level, accepted.group(), accepted.citizen(),
						accepted.citizen().x(), accepted.citizen().y(), accepted.citizen().z(), yRot) == null) {
					// Every body the machine is allowed is in use: the recruit is there, on the
					// books and at work, but is not shown. Said out loud, or it looks like a loss.
					player.sendSystemMessage(Component.translatable("message.villagerciv.recruit.unseen", accepted.citizen().name()));
				}
				if (accepted.group() instanceof Settlement settlement) {
					player.sendSystemMessage(Component.translatable("message.villagerciv.recruit.settled",
							accepted.citizen().name(), settlement.name(), settlement.size()));
				} else {
					player.sendSystemMessage(Component.translatable("message.villagerciv.recruit.joined",
							accepted.citizen().name(), EmbodimentController.roleName(accepted.citizen().role()),
							accepted.group().size(), Recruitment.MAX_EXPEDITION_SIZE));
				}
			}
			case Recruitment.Refused refused -> {
				if (refused.retryInTicks() == 0) {
					feedback(level, villager, ParticleTypes.ANGRY_VILLAGER, SoundEvents.VILLAGER_NO);
					player.sendSystemMessage(Component.translatable("message.villagerciv.recruit.refused"), true);
				} else {
					player.sendSystemMessage(Component.translatable("message.villagerciv.recruit.cooldown",
							minutes(refused.retryInTicks())), true);
				}
			}
			case Recruitment.ExpeditionFull _ -> player.sendSystemMessage(
					Component.translatable("message.villagerciv.recruit.full", Recruitment.MAX_EXPEDITION_SIZE), true);
		}
	}

	private static String professionOf(Villager villager) {
		return villager.getVillagerData().profession().unwrapKey()
				.map(ResourceKey::identifier).map(id -> id.getPath()).orElse("none");
	}

	/** Game ticks as real minutes (1200 ticks each), rounded up. */
	private static int minutes(long ticks) {
		return (int) ((ticks + 1199) / 1200);
	}

	private static void feedback(ServerLevel level, Villager villager, ParticleOptions particle, SoundEvent sound) {
		level.sendParticles(particle, villager.getX(), villager.getY() + 1.6, villager.getZ(), 8, 0.35, 0.35, 0.35, 0.0);
		level.playSound(null, villager.blockPosition(), sound, SoundSource.NEUTRAL, 1.0f, 1.0f);
	}
}
