package com.sunnatbek.villagerciv.mod.net;

import com.sunnatbek.villagerciv.mod.ModContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Server to client: what a villager would say to an offer, shown before the scroll is spent.
 *
 * @param cooldownMinutes real minutes until the villager can be asked again, 0 if it can be asked now
 */
public record RecruitOfferPayload(int villagerEntityId, String profession, int chancePercent, boolean unemployed, boolean night,
		int cooldownMinutes, int expeditionSize, int maxExpeditionSize) implements CustomPacketPayload {
	public static final Type<RecruitOfferPayload> TYPE = new Type<>(ModContent.id("recruit_offer"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RecruitOfferPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeVarInt(p.villagerEntityId);
				buf.writeUtf(p.profession);
				buf.writeVarInt(p.chancePercent);
				buf.writeBoolean(p.unemployed);
				buf.writeBoolean(p.night);
				buf.writeVarInt(p.cooldownMinutes);
				buf.writeVarInt(p.expeditionSize);
				buf.writeVarInt(p.maxExpeditionSize);
			},
			buf -> new RecruitOfferPayload(buf.readVarInt(), buf.readUtf(), buf.readVarInt(), buf.readBoolean(), buf.readBoolean(),
					buf.readVarInt(), buf.readVarInt(), buf.readVarInt()));

	@Override
	public Type<RecruitOfferPayload> type() {
		return TYPE;
	}
}
