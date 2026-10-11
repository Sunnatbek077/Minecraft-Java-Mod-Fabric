package com.sunnatbek.villagerciv.mod.net;

import com.sunnatbek.villagerciv.mod.ModContent;
import com.sunnatbek.villagerciv.sim.Activity;
import com.sunnatbek.villagerciv.sim.Role;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/** Server to client: a snapshot of the player's expedition for the expedition screen. */
public record ExpeditionInfoPayload(boolean following, int food, boolean starving, List<Member> members) implements CustomPacketPayload {
	public record Member(String name, Role role, Activity activity, int health, int loyalty) {
		public static void write(RegistryFriendlyByteBuf buf, List<Member> members) {
			buf.writeVarInt(members.size());
			for (Member member : members) {
				buf.writeUtf(member.name);
				buf.writeVarInt(member.role.ordinal());
				buf.writeVarInt(member.activity.ordinal());
				buf.writeVarInt(member.health);
				buf.writeVarInt(member.loyalty);
			}
		}

		public static List<Member> read(RegistryFriendlyByteBuf buf) {
			int count = buf.readVarInt();
			List<Member> members = new ArrayList<>(count);
			Role[] roles = Role.values();
			Activity[] activities = Activity.values();
			for (int i = 0; i < count; i++) {
				members.add(new Member(buf.readUtf(), roles[buf.readVarInt()], activities[buf.readVarInt()], buf.readVarInt(), buf.readVarInt()));
			}
			return members;
		}
	}

	public static final Type<ExpeditionInfoPayload> TYPE = new Type<>(ModContent.id("expedition_info"));
	public static final StreamCodec<RegistryFriendlyByteBuf, ExpeditionInfoPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeBoolean(p.following);
				buf.writeVarInt(p.food);
				buf.writeBoolean(p.starving);
				Member.write(buf, p.members);
			},
			buf -> {
				boolean following = buf.readBoolean();
				int food = buf.readVarInt();
				boolean starving = buf.readBoolean();
				return new ExpeditionInfoPayload(following, food, starving, Member.read(buf));
			});

	@Override
	public Type<ExpeditionInfoPayload> type() {
		return TYPE;
	}
}
