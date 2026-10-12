package com.sunnatbek.villagerciv.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sunnatbek.villagerciv.api.SettlementId;
import com.sunnatbek.villagerciv.build.Blueprint;
import com.sunnatbek.villagerciv.build.Building;
import com.sunnatbek.villagerciv.build.Street;
import com.sunnatbek.villagerciv.build.WallPiece;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Policy;
import com.sunnatbek.villagerciv.sim.Request;
import com.sunnatbek.villagerciv.sim.StockStatus;
import com.sunnatbek.villagerciv.sim.ToolKind;
import com.sunnatbek.villagerciv.sim.WorkSite;
import com.sunnatbek.villagerciv.sim.Settlement;
import com.sunnatbek.villagerciv.sim.SettlementStage;
import com.sunnatbek.villagerciv.sim.SiteScore;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.BitSet;
import java.util.UUID;

final class SettlementCodec {
	private SettlementCodec() {
	}

	static JsonObject write(Settlement settlement, int schema) {
		JsonObject json = new JsonObject();
		json.addProperty(SchemaMigrations.VERSION_KEY, schema);
		json.addProperty("id", settlement.id().toString());
		json.addProperty("name", settlement.name());
		json.addProperty("founder", settlement.founder().toString());
		json.addProperty("foundedTick", settlement.foundedTick());
		json.addProperty("anchorX", settlement.anchorX());
		json.addProperty("anchorY", settlement.anchorY());
		json.addProperty("anchorZ", settlement.anchorZ());
		json.addProperty("stage", settlement.stage().name());

		JsonObject ledger = new JsonObject();
		for (Commodity commodity : Commodity.values()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("onHand", settlement.ledger().onHand(commodity));
			entry.addProperty("reserved", settlement.ledger().reserved(commodity));
			ledger.add(commodity.name(), entry);
		}
		json.add("ledger", ledger);

		JsonObject contributions = new JsonObject();
		settlement.contributions().forEach((player, units) -> contributions.addProperty(player.toString(), units));
		json.add("contributions", contributions);

		JsonArray territory = new JsonArray();
		for (long chunk : settlement.territory()) {
			territory.add(chunk);
		}
		json.add("territory", territory);

		SiteScore score = settlement.siteScore();
		JsonObject site = new JsonObject();
		site.addProperty("total", score.total());
		site.addProperty("water", score.water());
		site.addProperty("wood", score.wood());
		site.addProperty("stone", score.stone());
		site.addProperty("flatness", score.flatness());
		site.addProperty("fertility", score.fertility());
		site.addProperty("defense", score.defense());
		json.add("site", site);

		Settlement.PlotMap plots = settlement.plots();
		JsonObject plotJson = new JsonObject();
		plotJson.addProperty("originX", plots.originX());
		plotJson.addProperty("originZ", plots.originZ());
		plotJson.addProperty("size", plots.size());
		plotJson.addProperty("buildable", Base64.getEncoder().encodeToString(plots.buildable().toByteArray()));
		json.add("plots", plotJson);

		JsonObject tools = new JsonObject();
		for (ToolKind kind : ToolKind.values()) {
			tools.addProperty(kind.name(), settlement.tools(kind));
		}
		json.add("tools", tools);
		json.addProperty("threat", settlement.threat());
		json.addProperty("lastThreatTick", settlement.lastThreatTick());
		json.addProperty("wallPieces", settlement.wallPieces());
		json.addProperty("expansion", settlement.expansion());
		json.addProperty("wallShape", settlement.wallShape().name());
		if (settlement.plannedShape() != null) {
			json.addProperty("plannedShape", settlement.plannedShape().name());
		}
		JsonArray wall = new JsonArray();
		for (WallPiece piece : settlement.wallPlan()) {
			JsonObject w = new JsonObject();
			w.addProperty("blueprint", piece.blueprint());
			w.addProperty("x", piece.x());
			w.addProperty("z", piece.z());
			w.addProperty("rotation", piece.rotation());
			w.addProperty("sizeX", piece.sizeX());
			w.addProperty("sizeZ", piece.sizeZ());
			wall.add(w);
		}
		json.add("wallPlan", wall);
		JsonArray plans = new JsonArray();
		for (Policy plan : settlement.plans()) {
			plans.add(plan.name());
		}
		json.add("plans", plans);
		JsonObject statuses = new JsonObject();
		for (Commodity commodity : Commodity.values()) {
			JsonObject entry = new JsonObject();
			entry.addProperty("status", settlement.status(commodity).name());
			entry.addProperty("since", settlement.statusSinceTick(commodity));
			entry.addProperty("askAgain", settlement.askAgainTick(commodity));
			statuses.add(commodity.name(), entry);
		}
		json.add("statuses", statuses);
		JsonArray requests = new JsonArray();
		for (Request request : settlement.requests()) {
			JsonObject r = new JsonObject();
			r.addProperty("id", request.id());
			r.addProperty("commodity", request.commodity().name());
			r.addProperty("amount", request.amount());
			r.addProperty("delivered", request.delivered());
			r.addProperty("purpose", request.purpose());
			r.addProperty("createdTick", request.createdTick());
			r.addProperty("deadlineTick", request.deadlineTick());
			requests.add(r);
		}
		json.add("requests", requests);
		JsonObject reputation = new JsonObject();
		settlement.reputationTenths().forEach((player, tenths) -> reputation.addProperty(player.toString(), tenths));
		json.add("reputation", reputation);
		json.addProperty("morale", settlement.morale());
		json.addProperty("promotionSinceTick", settlement.promotionSinceTick());
		json.addProperty("lastPopulationTick", settlement.lastPopulationTick());
		json.addProperty("hunger", settlement.hunger());
		json.addProperty("lastUpkeepTick", settlement.lastUpkeepTick());
		json.addProperty("starving", settlement.isStarving());
		JsonArray sites = new JsonArray();
		for (WorkSite workSite : settlement.sites()) {
			JsonObject siteJson = new JsonObject();
			siteJson.addProperty("id", workSite.id());
			siteJson.addProperty("type", workSite.type().name());
			siteJson.addProperty("x", workSite.x());
			siteJson.addProperty("z", workSite.z());
			if (workSite.y() != WorkSite.Y_UNKNOWN) {
				siteJson.addProperty("y", workSite.y());
			}
			siteJson.addProperty("progress", workSite.progress());
			siteJson.addProperty("exhausted", workSite.isExhausted());
			sites.add(siteJson);
		}
		json.add("sites", sites);

		JsonArray streets = new JsonArray();
		for (Street street : settlement.streets()) {
			JsonObject s = new JsonObject();
			s.addProperty("x", street.x());
			s.addProperty("z", street.z());
			s.addProperty("dx", street.dx());
			s.addProperty("dz", street.dz());
			s.addProperty("length", street.length());
			s.addProperty("paved", street.paved());
			streets.add(s);
		}
		json.add("streets", streets);
		JsonArray buildings = new JsonArray();
		for (Building building : settlement.buildings()) {
			JsonObject b = new JsonObject();
			b.addProperty("id", building.id());
			b.addProperty("blueprint", building.blueprint());
			b.addProperty("category", building.category().name());
			b.addProperty("x", building.x());
			b.addProperty("z", building.z());
			if (building.y() != Building.Y_UNKNOWN) {
				b.addProperty("y", building.y());
			}
			b.addProperty("rotation", building.rotation());
			b.addProperty("sizeX", building.sizeX());
			b.addProperty("sizeZ", building.sizeZ());
			b.addProperty("street", building.street());
			b.addProperty("paveFrom", building.paveFrom());
			b.addProperty("paveTo", building.paveTo());
			b.addProperty("totalSteps", building.totalSteps());
			b.addProperty("beds", building.beds());
			b.addProperty("costWood", building.costWood());
			b.addProperty("costStone", building.costStone());
			b.addProperty("stage", building.stage().name());
			b.addProperty("progress", building.progress());
			b.addProperty("applied", building.applied());
			b.addProperty("design", building.design());
			buildings.add(b);
		}
		json.add("buildings", buildings);

		JsonArray members = new JsonArray();
		for (Citizen citizen : settlement.members()) {
			members.add(ExpeditionCodec.writeCitizen(citizen));
		}
		json.add("members", members);
		return json;
	}

	static Settlement read(JsonObject json) {
		try {
			Settlement settlement = new Settlement(
					SettlementId.parse(json.get("id").getAsString()),
					json.get("name").getAsString(),
					UUID.fromString(json.get("founder").getAsString()),
					json.get("foundedTick").getAsLong(),
					json.get("anchorX").getAsInt(), json.get("anchorY").getAsInt(), json.get("anchorZ").getAsInt());
			settlement.setStage(SettlementStage.valueOf(json.get("stage").getAsString()));

			JsonObject ledger = json.getAsJsonObject("ledger");
			for (Commodity commodity : Commodity.values()) {
				// A commodity added by a later mod version is simply absent from older saves.
				if (ledger.has(commodity.name())) {
					JsonObject entry = ledger.getAsJsonObject(commodity.name());
					settlement.ledger().restore(commodity, entry.get("onHand").getAsInt(), entry.get("reserved").getAsInt());
				}
			}
			if (json.has("contributions")) {
				for (var entry : json.getAsJsonObject("contributions").entrySet()) {
					settlement.restoreContribution(UUID.fromString(entry.getKey()), entry.getValue().getAsInt());
				}
			}
			for (JsonElement chunk : json.getAsJsonArray("territory")) {
				settlement.claim(chunk.getAsLong());
			}

			JsonObject site = json.getAsJsonObject("site");
			SiteScore score = new SiteScore(site.get("total").getAsInt(), site.get("water").getAsInt(), site.get("wood").getAsInt(),
					site.get("stone").getAsInt(), site.get("flatness").getAsInt(), site.get("fertility").getAsInt(),
					site.get("defense").getAsInt(), SiteScore.Problem.NONE);
			JsonObject plots = json.getAsJsonObject("plots");
			settlement.setSurvey(score, new Settlement.PlotMap(plots.get("originX").getAsInt(), plots.get("originZ").getAsInt(),
					plots.get("size").getAsInt(), BitSet.valueOf(Base64.getDecoder().decode(plots.get("buildable").getAsString()))));

			if (json.has("tools")) {
				JsonObject tools = json.getAsJsonObject("tools");
				for (ToolKind kind : ToolKind.values()) {
					if (tools.has(kind.name())) {
						settlement.restoreTools(kind, tools.get(kind.name()).getAsInt());
					}
				}
			} else {
				// Tools given before they were kept by kind: nobody knows what they were, so they
				// are taken to have been an even mix.
				int each = settlement.ledger().onHand(Commodity.TOOLS) / ToolKind.values().length;
				for (ToolKind kind : ToolKind.values()) {
					settlement.restoreTools(kind, each);
				}
			}
			if (json.has("wallPlan")) {
				settlement.setThreat(json.get("threat").getAsInt());
				settlement.setLastThreatTick(json.get("lastThreatTick").getAsLong());
				List<WallPiece> pieces = new ArrayList<>();
				for (JsonElement element : json.getAsJsonArray("wallPlan")) {
					JsonObject w = element.getAsJsonObject();
					pieces.add(new WallPiece(w.get("blueprint").getAsString(), w.get("x").getAsInt(), w.get("z").getAsInt(),
							w.get("rotation").getAsInt(), w.get("sizeX").getAsInt(), w.get("sizeZ").getAsInt()));
				}
				settlement.setWallPlan(pieces, json.get("wallPieces").getAsInt());
				if (json.has("expansion")) {
					settlement.setExpansion(json.get("expansion").getAsInt());
				}
				if (json.has("plannedShape")) {
					settlement.setPlannedShape(com.sunnatbek.villagerciv.build.ShapedWall.Shape.valueOf(json.get("plannedShape").getAsString()));
				}
				if (json.has("wallShape")) {
					settlement.setWallShape(com.sunnatbek.villagerciv.build.ShapedWall.Shape.valueOf(json.get("wallShape").getAsString()));
				}
			}
			if (json.has("plans")) {
				for (JsonElement plan : json.getAsJsonArray("plans")) {
					settlement.adoptPlan(Policy.valueOf(plan.getAsString()));
				}
			} else if (json.has("policy")) {
				// Saved while a settlement could follow only one plan.
				settlement.adoptPlan(Policy.valueOf(json.get("policy").getAsString()));
			}
			if (json.has("statuses")) {
				JsonObject statuses = json.getAsJsonObject("statuses");
				for (Commodity commodity : Commodity.values()) {
					if (statuses.has(commodity.name())) {
						JsonObject entry = statuses.getAsJsonObject(commodity.name());
						settlement.restoreStatus(commodity, StockStatus.valueOf(entry.get("status").getAsString()),
								entry.get("since").getAsLong(), entry.get("askAgain").getAsLong());
					}
				}
				for (JsonElement element : json.getAsJsonArray("requests")) {
					JsonObject r = element.getAsJsonObject();
					settlement.restoreRequest(new Request(r.get("id").getAsInt(), Commodity.valueOf(r.get("commodity").getAsString()),
							r.get("amount").getAsInt(), r.get("delivered").getAsInt(), r.get("purpose").getAsString(),
							r.get("createdTick").getAsLong(), r.get("deadlineTick").getAsLong()));
				}
				for (var entry : json.getAsJsonObject("reputation").entrySet()) {
					settlement.addReputation(UUID.fromString(entry.getKey()), entry.getValue().getAsInt());
				}
			}
			if (json.has("morale")) {
				settlement.setMorale(json.get("morale").getAsInt());
				settlement.setPromotionSinceTick(json.get("promotionSinceTick").getAsLong());
				settlement.setLastPopulationTick(json.get("lastPopulationTick").getAsLong());
			}
			// Added with labor; a settlement saved before that simply has none of it yet.
			if (json.has("sites")) {
				settlement.setHunger(json.get("hunger").getAsInt());
				settlement.setLastUpkeepTick(json.get("lastUpkeepTick").getAsLong());
				settlement.setStarving(json.get("starving").getAsBoolean());
				for (JsonElement element : json.getAsJsonArray("sites")) {
					JsonObject siteJson = element.getAsJsonObject();
					WorkSite workSite = settlement.restoreSite(siteJson.get("id").getAsInt(), JobType.valueOf(siteJson.get("type").getAsString()),
							siteJson.get("x").getAsInt(), siteJson.get("z").getAsInt());
					if (siteJson.has("y")) {
						workSite.setY(siteJson.get("y").getAsInt());
					}
					workSite.setProgress(siteJson.get("progress").getAsInt());
					workSite.setExhausted(siteJson.get("exhausted").getAsBoolean());
				}
			}
			if (json.has("streets")) {
				for (JsonElement element : json.getAsJsonArray("streets")) {
					JsonObject s = element.getAsJsonObject();
					settlement.addStreet(new Street(s.get("x").getAsInt(), s.get("z").getAsInt(), s.get("dx").getAsInt(),
							s.get("dz").getAsInt(), s.get("length").getAsInt(), s.get("paved").getAsInt()));
				}
				for (JsonElement element : json.getAsJsonArray("buildings")) {
					JsonObject b = element.getAsJsonObject();
					Building building = new Building(b.get("id").getAsInt(), b.get("blueprint").getAsString(),
							Blueprint.Category.valueOf(b.get("category").getAsString()), b.get("x").getAsInt(), b.get("z").getAsInt(),
							b.get("rotation").getAsInt(), b.get("sizeX").getAsInt(), b.get("sizeZ").getAsInt(), b.get("street").getAsInt(),
							b.get("paveFrom").getAsInt(), b.get("paveTo").getAsInt(), b.get("totalSteps").getAsInt(),
							b.get("beds").getAsInt(), b.get("costWood").getAsInt(), b.get("costStone").getAsInt(), settlement::markDirty);
					if (b.has("y")) {
						building.setY(b.get("y").getAsInt());
					}
					building.setStage(Building.Stage.valueOf(b.get("stage").getAsString()));
					building.setCursors(b.get("progress").getAsInt(), b.get("applied").getAsInt());
					if (b.has("design")) {
						building.setDesign(b.get("design").getAsInt());
					}
					settlement.restoreBuilding(building);
				}
			}
			for (JsonElement member : json.getAsJsonArray("members")) {
				settlement.add(ExpeditionCodec.readCitizen(member.getAsJsonObject()));
			}
			settlement.markClean();
			return settlement;
		} catch (RuntimeException e) {
			throw new SaveFormatException("invalid settlement record: " + e);
		}
	}
}
