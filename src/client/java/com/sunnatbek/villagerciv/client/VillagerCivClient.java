package com.sunnatbek.villagerciv.client;

import com.sunnatbek.villagerciv.mod.ModContent;
import com.sunnatbek.villagerciv.mod.net.ExpeditionInfoPayload;
import com.sunnatbek.villagerciv.mod.net.RecruitOfferPayload;
import com.sunnatbek.villagerciv.mod.net.SettlementInfoPayload;
import com.sunnatbek.villagerciv.mod.net.SiteReportPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.renderer.entity.EntityRenderers;

public final class VillagerCivClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		EntityRenderers.register(ModContent.CITIZEN, CitizenRenderer::new);
		ClientPlayNetworking.registerGlobalReceiver(RecruitOfferPayload.TYPE,
				(payload, context) -> context.client().gui.setScreen(new RecruitOfferScreen(payload)));
		ClientPlayNetworking.registerGlobalReceiver(ExpeditionInfoPayload.TYPE,
				(payload, context) -> context.client().gui.setScreen(new ExpeditionScreen(payload)));
		ClientPlayNetworking.registerGlobalReceiver(SiteReportPayload.TYPE,
				(payload, context) -> context.client().gui.setScreen(new SiteReportScreen(payload)));
		ClientPlayNetworking.registerGlobalReceiver(SettlementInfoPayload.TYPE,
				(payload, context) -> context.client().gui.setScreen(new SettlementScreen(payload)));
	}
}
