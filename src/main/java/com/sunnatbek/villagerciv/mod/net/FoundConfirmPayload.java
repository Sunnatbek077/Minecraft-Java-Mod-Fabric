package com.sunnatbek.villagerciv.mod.net;

import com.sunnatbek.villagerciv.mod.ModContent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client to server: the player accepts the surveyed place; found the settlement there. */
public record FoundConfirmPayload(BlockPos site) implements CustomPacketPayload {
	public static final Type<FoundConfirmPayload> TYPE = new Type<>(ModContent.id("found_confirm"));
	public static final StreamCodec<RegistryFriendlyByteBuf, FoundConfirmPayload> CODEC = StreamCodec.of(
			(buf, p) -> buf.writeBlockPos(p.site),
			buf -> new FoundConfirmPayload(buf.readBlockPos()));

	@Override
	public Type<FoundConfirmPayload> type() {
		return TYPE;
	}
}
