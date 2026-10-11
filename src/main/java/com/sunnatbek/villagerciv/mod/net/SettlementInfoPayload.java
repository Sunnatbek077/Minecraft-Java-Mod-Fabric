package com.sunnatbek.villagerciv.mod.net;

import com.sunnatbek.villagerciv.mod.ModContent;
import com.sunnatbek.villagerciv.build.Construction;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.Policy;
import com.sunnatbek.villagerciv.sim.SettlementStage;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * Server to client: a snapshot of a settlement for the settlement screen.
 *
 * @param stock            on-hand amount per commodity, indexed by {@code Commodity.ordinal()}
 * @param toolsHave        tools in store per kind, indexed by {@code ToolKind.ordinal()}
 * @param toolsNeeded      tools the settlement could put to use right now, same indexing
 * @param morale           0–100, how the settlement is faring
 * @param beds             beds in finished buildings
 * @param yourContribution units the viewing player has given to this settlement so far
 * @param building         what the builders are up to
 * @param project          name of the building under construction, or of the one waited for, or empty
 * @param needWood         wood the building waited for takes (0 unless one is waited for), and likewise stone
 * @param projectPercent   how far along it is
 * @param plans            the Development Plans followed, as a bit set over {@code Policy.ordinal()}
 * @param statuses         stock status per commodity ({@code StockStatus.ordinal()}), indexed like {@code stock}
 * @param threat           how much the settlement has suffered from attacks lately
 * @param guards           citizens on guard duty
 * @param wallBuilt        finished pieces of the wall, out of {@code wallTotal} (0 if none is planned)
 * @param reputation       the viewing player's standing here
 * @param requests         open requests to the patron
 */
public record SettlementInfoPayload(String name, SettlementStage stage, boolean patron, int[] stock, int[] toolsHave,
		int[] toolsNeeded, int morale, int beds, int territoryChunks,
		int siteQuality, int yourContribution, Construction.Status building, String project, int projectPercent,
		int needWood, int needStone,
		int plans, int[] statuses, int threat, int guards, int wallBuilt, int wallTotal, int reputation, List<RequestLine> requests,
		List<ExpeditionInfoPayload.Member> members) implements CustomPacketPayload {
	/**
	 * One open request.
	 *
	 * @param purpose  name of the building it is for, or empty for the food reserve
	 * @param daysLeft game days until it lapses, rounded up
	 */
	public record RequestLine(Commodity commodity, int amount, int delivered, String purpose, int daysLeft) {
	}

	public boolean hasPlan(Policy plan) {
		return (plans & (1 << plan.ordinal())) != 0;
	}

	public static final Type<SettlementInfoPayload> TYPE = new Type<>(ModContent.id("settlement_info"));
	public static final StreamCodec<RegistryFriendlyByteBuf, SettlementInfoPayload> CODEC = StreamCodec.of(
			(buf, p) -> {
				buf.writeUtf(p.name);
				buf.writeVarInt(p.stage.ordinal());
				buf.writeBoolean(p.patron);
				buf.writeVarIntArray(p.stock);
				buf.writeVarIntArray(p.toolsHave);
				buf.writeVarIntArray(p.toolsNeeded);
				buf.writeVarInt(p.morale);
				buf.writeVarInt(p.beds);
				buf.writeVarInt(p.territoryChunks);
				buf.writeVarInt(p.siteQuality);
				buf.writeVarInt(p.yourContribution);
				buf.writeVarInt(p.building.ordinal());
				buf.writeUtf(p.project);
				buf.writeVarInt(p.projectPercent);
				buf.writeVarInt(p.needWood);
				buf.writeVarInt(p.needStone);
				buf.writeVarInt(p.plans);
				buf.writeVarIntArray(p.statuses);
				buf.writeVarInt(p.threat);
				buf.writeVarInt(p.guards);
				buf.writeVarInt(p.wallBuilt);
				buf.writeVarInt(p.wallTotal);
				buf.writeVarInt(p.reputation);
				buf.writeVarInt(p.requests.size());
				for (RequestLine request : p.requests) {
					buf.writeVarInt(request.commodity.ordinal());
					buf.writeVarInt(request.amount);
					buf.writeVarInt(request.delivered);
					buf.writeUtf(request.purpose);
					buf.writeVarInt(request.daysLeft);
				}
				ExpeditionInfoPayload.Member.write(buf, p.members);
			},
			buf -> {
				String name = buf.readUtf();
				SettlementStage stage = SettlementStage.values()[buf.readVarInt()];
				boolean patron = buf.readBoolean();
				int[] stock = buf.readVarIntArray();
				int[] toolsHave = buf.readVarIntArray();
				int[] toolsNeeded = buf.readVarIntArray();
				int morale = buf.readVarInt();
				int beds = buf.readVarInt();
				int territoryChunks = buf.readVarInt();
				int siteQuality = buf.readVarInt();
				int yourContribution = buf.readVarInt();
				Construction.Status building = Construction.Status.values()[buf.readVarInt()];
				String project = buf.readUtf();
				int projectPercent = buf.readVarInt();
				int needWood = buf.readVarInt();
				int needStone = buf.readVarInt();
				int plans = buf.readVarInt();
				int[] statuses = buf.readVarIntArray();
				int threat = buf.readVarInt();
				int guards = buf.readVarInt();
				int wallBuilt = buf.readVarInt();
				int wallTotal = buf.readVarInt();
				int reputation = buf.readVarInt();
				int requestCount = buf.readVarInt();
				List<RequestLine> requests = new ArrayList<>(requestCount);
				for (int i = 0; i < requestCount; i++) {
					requests.add(new RequestLine(Commodity.values()[buf.readVarInt()], buf.readVarInt(), buf.readVarInt(), buf.readUtf(), buf.readVarInt()));
				}
				return new SettlementInfoPayload(name, stage, patron, stock, toolsHave, toolsNeeded, morale, beds, territoryChunks,
						siteQuality, yourContribution, building, project, projectPercent, needWood, needStone, plans, statuses, threat, guards, wallBuilt, wallTotal, reputation, requests,
						ExpeditionInfoPayload.Member.read(buf));
			});

	@Override
	public Type<SettlementInfoPayload> type() {
		return TYPE;
	}
}
