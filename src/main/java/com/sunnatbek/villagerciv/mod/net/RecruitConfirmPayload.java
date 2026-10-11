package com.sunnatbek.villagerciv.mod.net;

import com.sunnatbek.villagerciv.mod.ModContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player hands the scroll to this villager. */
public record RecruitConfirmPayload(int villagerEntityId) implements CustomPacketPayload {
	public static final Type<RecruitConfirmPayload> TYPE = new Type<>(ModContent.id("recruit_confirm"));
	public static final StreamCodec<RegistryFriendlyByteBuf, RecruitConfirmPayload> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeVarInt(p.villagerEntityId),
			buf -> new RecruitConfirmPayload(buf.readVarInt()));

	@Override
	public Type<RecruitConfirmPayload> type() {
		return TYPE;
	}
}
