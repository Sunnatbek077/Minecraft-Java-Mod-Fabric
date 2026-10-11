package com.sunnatbek.villagerciv.persistence;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sunnatbek.villagerciv.api.CitizenId;
import com.sunnatbek.villagerciv.api.ExpeditionId;
import com.sunnatbek.villagerciv.sim.Citizen;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.JobType;
import com.sunnatbek.villagerciv.sim.Expedition;
import com.sunnatbek.villagerciv.sim.Role;
import com.sunnatbek.villagerciv.sim.Trait;

import java.util.EnumSet;
import java.util.UUID;

final class ExpeditionCodec {
	private ExpeditionCodec() {
	}

	static JsonObject write(Expedition expedition, int schema) {
		JsonObject json = new JsonObject();
		json.addProperty(SchemaMigrations.VERSION_KEY, schema);
		json.addProperty("id", expedition.id().toString());
		json.addProperty("player", expedition.player().toString());
		json.addProperty("createdTick", expedition.createdTick());
		json.addProperty("state", expedition.state().name());
		json.addProperty("food", expedition.food());
		json.addProperty("hunger", expedition.hunger());
		json.addProperty("lastUpkeepTick", expedition.lastUpkeepTick());
		json.addProperty("starving", expedition.isStarving());
		JsonArray members = new JsonArray();
		for (Citizen citizen : expedition.members()) {
			members.add(writeCitizen(citizen));
		}
		json.add("members", members);
		return json;
	}

	static Expedition read(JsonObject json) {
		try {
			Expedition expedition = new Expedition(
					ExpeditionId.parse(json.get("id").getAsString()),
					UUID.fromString(json.get("player").getAsString()),
					json.get("createdTick").getAsLong());
			for (JsonElement member : json.getAsJsonArray("members")) {
				expedition.add(readCitizen(member.getAsJsonObject()));
			}
			expedition.setState(Expedition.State.valueOf(json.get("state").getAsString()));
			// Added after the first expeditions were saved: absent means "nothing yet".
			if (json.has("food")) {
				expedition.setFood(json.get("food").getAsInt());
				expedition.setHunger(json.get("hunger").getAsInt());
				expedition.setLastUpkeepTick(json.get("lastUpkeepTick").getAsLong());
				expedition.setStarving(json.get("starving").getAsBoolean());
			}
			expedition.markClean();
			return expedition;
		} catch (RuntimeException e) {
			throw new SaveFormatException("invalid expedition record: " + e);
		}
	}

	static JsonObject writeCitizen(Citizen citizen) {
		JsonObject json = new JsonObject();
		json.addProperty("id", citizen.id().toString());
		json.addProperty("name", citizen.name());
		json.addProperty("joinedTick", citizen.joinedTick());
		json.addProperty("affinity", citizen.affinity().name());
		JsonArray traits = new JsonArray();
		for (Trait trait : citizen.traits()) {
			traits.add(trait.name());
		}
		json.add("traits", traits);
		json.addProperty("role", citizen.role().name());
		json.addProperty("health", citizen.health());
		json.addProperty("loyalty", citizen.loyalty());
		json.addProperty("x", citizen.x());
		json.addProperty("y", citizen.y());
		json.addProperty("z", citizen.z());
		if (citizen.job() != null) {
			json.addProperty("job", citizen.job().name());
			json.addProperty("jobSite", citizen.jobSite());
			json.addProperty("leaseUntil", citizen.leaseUntil());
			json.addProperty("workBank", citizen.workBank());
		}
		if (citizen.adultAtTick() != 0) {
			json.addProperty("adultAtTick", citizen.adultAtTick());
		}
		if (citizen.lastChildTick() > 0) {
			json.addProperty("lastChildTick", citizen.lastChildTick());
		}
		if (citizen.isArmored()) {
			json.addProperty("armored", true);
		}
		if (citizen.isGuard()) {
			json.addProperty("guard", true);
		}
		if (citizen.home() != Citizen.NO_HOME) {
			json.addProperty("home", citizen.home());
			json.addProperty("bed", citizen.bed());
		}
		if (citizen.carrying() != null) {
			json.addProperty("carrying", citizen.carrying().name());
			json.addProperty("carryingAmount", citizen.carryingAmount());
		}
		return json;
	}

	static Citizen readCitizen(JsonObject json) {
		EnumSet<Trait> traits = EnumSet.noneOf(Trait.class);
		for (JsonElement trait : json.getAsJsonArray("traits")) {
			traits.add(Trait.valueOf(trait.getAsString()));
		}
		Citizen citizen = new Citizen(
				CitizenId.parse(json.get("id").getAsString()),
				json.get("name").getAsString(),
				json.get("joinedTick").getAsLong(),
				Role.valueOf(json.get("affinity").getAsString()),
				traits);
		citizen.setRole(Role.valueOf(json.get("role").getAsString()));
		citizen.setHealth(json.get("health").getAsFloat());
		citizen.setLoyalty(json.get("loyalty").getAsInt());
		citizen.setPosition(json.get("x").getAsDouble(), json.get("y").getAsDouble(), json.get("z").getAsDouble());
		if (json.has("job")) {
			citizen.assignJob(JobType.valueOf(json.get("job").getAsString()), json.get("jobSite").getAsInt(), json.get("leaseUntil").getAsLong());
			citizen.setWorkBank(json.get("workBank").getAsInt());
		}
		if (json.has("adultAtTick")) {
			citizen.setAdultAtTick(json.get("adultAtTick").getAsLong());
		}
		if (json.has("lastChildTick")) {
			citizen.setLastChildTick(json.get("lastChildTick").getAsLong());
		}
		if (json.has("armored")) {
			citizen.setArmored(json.get("armored").getAsBoolean());
		}
		if (json.has("guard")) {
			citizen.setGuard(json.get("guard").getAsBoolean());
		}
		if (json.has("home")) {
			citizen.setHome(json.get("home").getAsInt(), json.get("bed").getAsInt());
		}
		if (json.has("carrying")) {
			citizen.carry(Commodity.valueOf(json.get("carrying").getAsString()), json.get("carryingAmount").getAsInt());
		}
		return citizen;
	}
}
