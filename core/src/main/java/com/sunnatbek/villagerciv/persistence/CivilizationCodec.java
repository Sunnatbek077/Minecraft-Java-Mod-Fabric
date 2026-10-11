package com.sunnatbek.villagerciv.persistence;

import com.google.gson.JsonObject;
import com.sunnatbek.villagerciv.api.CivilizationId;
import com.sunnatbek.villagerciv.sim.Civilization;
import com.sunnatbek.villagerciv.sim.CivilizationTier;

final class CivilizationCodec {
	private CivilizationCodec() {
	}

	static JsonObject write(Civilization civilization, int schema) {
		JsonObject json = new JsonObject();
		json.addProperty(SchemaMigrations.VERSION_KEY, schema);
		json.addProperty("id", civilization.id().toString());
		json.addProperty("name", civilization.name());
		json.addProperty("foundedTick", civilization.foundedTick());
		json.addProperty("tier", civilization.tier().name());
		return json;
	}

	static Civilization read(JsonObject json) {
		try {
			return new Civilization(
					CivilizationId.parse(json.get("id").getAsString()),
					json.get("name").getAsString(),
					json.get("foundedTick").getAsLong(),
					CivilizationTier.valueOf(json.get("tier").getAsString()));
		} catch (RuntimeException e) {
			throw new SaveFormatException("invalid civilization record: " + e);
		}
	}
}
