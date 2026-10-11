package com.sunnatbek.villagerciv.mod.net;

import com.sunnatbek.villagerciv.mod.ModContent;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player sends their expedition home. */
public record ExpeditionDisbandPayload() implements CustomPacketPayload {
	public static final Type<ExpeditionDisbandPayload> TYPE = new Type<>(ModContent.id("expedition_disband"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ExpeditionDisbandPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
			},
			buf -> new ExpeditionDisbandPayload());

	@Override
	public Type<ExpeditionDisbandPayload> type() {
		return TYPE;
	}
}
