package com.sunnatbek.villagerciv.client;

import com.sunnatbek.villagerciv.mod.ModContent;
import com.sunnatbek.villagerciv.mod.net.RecruitConfirmPayload;
import com.sunnatbek.villagerciv.mod.net.RecruitOfferPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Shows a villager's terms before the player commits the scroll. */
final class RecruitOfferScreen extends PanelScreen {
	private static final int PANEL_WIDTH = 236;

	private final RecruitOfferPayload offer;
	private final ItemStack scroll = new ItemStack(ModContent.RECRUITMENT_SCROLL);

	RecruitOfferScreen(RecruitOfferPayload offer) {
		super(Component.translatable("screen.villagerciv.offer.title"));
		this.offer = offer;
	}

	private boolean full() {
		return offer.expeditionSize() >= offer.maxExpeditionSize();
	}

	private boolean canAsk() {
		return !full() && offer.cooldownMinutes() == 0;
	}

	@Override
	protected void init() {
		int factors = (offer.unemployed() ? 1 : 0) + (offer.night() ? 1 : 0);
		// title | villager row | chance with gauge and factors, or the reason it cannot be asked | terms
		int body = canAsk() ? 10 + 8 + 4 + factors * 10 + 6 + 2 * 10 : 2 * 10;
		layoutPanel(PANEL_WIDTH, PADDING + 10 + 6 + SLOT_SIZE + 8 + body + PADDING);

		int buttonY = panelY + panelHeight + 6;
		Button confirm = Button.builder(Component.translatable("screen.villagerciv.offer.confirm"), _ -> {
			ClientPlayNetworking.send(new RecruitConfirmPayload(offer.villagerEntityId()));
			onClose();
		}).bounds(width / 2 - 114, buttonY, 110, 20).build();
		confirm.active = canAsk();
		addRenderableWidget(confirm);
		addRenderableWidget(Button.builder(Component.translatable("screen.villagerciv.offer.cancel"), _ -> onClose())
				.bounds(width / 2 + 4, buttonY, 110, 20).build());
	}

	@Override
	protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = panelX + PADDING;
		int right = panelX + panelWidth - PADDING;
		int y = panelY + PADDING;
		centredLabel(graphics, title, y, TEXT);

		y += 10 + 6;
		slot(graphics, scroll, left, y);
		label(graphics, Component.translatable("screen.villagerciv.offer.profession",
				Component.translatable("entity.minecraft.villager." + offer.profession())), left + SLOT_SIZE + 6, y, TEXT);
		label(graphics, Component.translatable("screen.villagerciv.offer.size", offer.expeditionSize(), offer.maxExpeditionSize()),
				left + SLOT_SIZE + 6, y + 10, full() ? BAD : MUTED);

		y += SLOT_SIZE + 8;
		if (full()) {
			wrappedCentred(graphics, Component.translatable("screen.villagerciv.offer.full", offer.maxExpeditionSize()), y, BAD);
			return;
		}
		if (offer.cooldownMinutes() > 0) {
			wrappedCentred(graphics, Component.translatable("screen.villagerciv.offer.cooldown", offer.cooldownMinutes()), y, BAD);
			return;
		}

		int colour = gradeColour(offer.chancePercent());
		label(graphics, Component.translatable("screen.villagerciv.offer.chance_label"), left, y, TEXT);
		rightLabel(graphics, Component.literal(offer.chancePercent() + "%"), right, y, colour);
		y += 10;
		bar(graphics, left, y, panelWidth - 2 * PADDING, 6, offer.chancePercent(), colour);
		y += 8 + 4;
		if (offer.unemployed()) {
			label(graphics, Component.translatable("screen.villagerciv.offer.unemployed"), left, y, GOOD);
			y += 10;
		}
		if (offer.night()) {
			label(graphics, Component.translatable("screen.villagerciv.offer.night"), left, y, BAD);
			y += 10;
		}
		divider(graphics, y + 1);
		y += 6;
		label(graphics, Component.translatable("screen.villagerciv.offer.cost"), left, y, MUTED);
		label(graphics, Component.translatable("screen.villagerciv.offer.refusal"), left, y + 10, MUTED);
	}
}
