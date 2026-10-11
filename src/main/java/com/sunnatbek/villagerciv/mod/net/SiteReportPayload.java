package com.sunnatbek.villagerciv.mod.net;

import com.sunnatbek.villagerciv.mod.ModContent;
import com.sunnatbek.villagerciv.sim.SiteScore;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Server to client: the Leader's judgement of the place chosen with the Founding Charter. */
public record SiteReportPayload(BlockPos site, String leaderName, SiteScore score) implements CustomPacketPayload {
	public static final Type<SiteReportPayload> TYPE = new Type<>(ModContent.id("site_report"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SiteReportPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBlockPos(p.site);
				buf.writeUtf(p.leaderName);
				SiteScore s = p.score;
				buf.writeVarInt(s.total());
				buf.writeVarInt(s.water());
				buf.writeVarInt(s.wood());
				buf.writeVarInt(s.stone());
				buf.writeVarInt(s.flatness());
				buf.writeVarInt(s.fertility());
				buf.writeVarInt(s.defense());
				buf.writeVarInt(s.problem().ordinal());
			},
			buf -> new SiteReportPayload(buf.readBlockPos(), buf.readUtf(), new SiteScore(buf.readVarInt(), buf.readVarInt(),
					buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(), buf.readVarInt(),
					SiteScore.Problem.values()[buf.readVarInt()])));

	@Override
	public Type<SiteReportPayload> type() {
		return TYPE;
	}
}
