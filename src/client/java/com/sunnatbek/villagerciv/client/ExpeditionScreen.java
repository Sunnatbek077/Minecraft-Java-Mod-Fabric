package com.sunnatbek.villagerciv.client;

import com.sunnatbek.villagerciv.mod.net.ExpeditionDisbandPayload;
import com.sunnatbek.villagerciv.mod.net.ExpeditionInfoPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.List;

/** The player's view of their expedition: who is in it, how they are doing, and the food supply. */
final class ExpeditionScreen extends PanelScreen {
	private static final int PANEL_WIDTH = 256;
	private static final ItemStack FOOD_ICON = new ItemStack(Items.BREAD);

	private final ExpeditionInfoPayload info;
	private boolean disbandArmed;
	private int foodY;
	private int tableY;

	ExpeditionScreen(ExpeditionInfoPayload info) {
		super(Component.translatable("screen.villagerciv.expedition.title"));
		this.info = info;
	}

	@Override
	protected void init() {
		int rows = Math.max(1, info.members().size());
		// title, state | food | members
		layoutPanel(PANEL_WIDTH, PADDING + 10 + 12 + 6 + SLOT_SIZE + 8 + memberTableHeight(rows) + PADDING);
		foodY = panelY + PADDING + 10 + 12 + 6;
		tableY = foodY + SLOT_SIZE + 8;

		int buttonY = panelY + panelHeight + 6;
		addRenderableWidget(Button.builder(Component.translatable("screen.villagerciv.expedition.disband"), button -> {
			// Sending everyone home cannot be undone, so it takes two clicks.
			if (!disbandArmed) {
				disbandArmed = true;
				button.setMessage(Component.translatable("screen.villagerciv.expedition.disband_confirm").withStyle(ChatFormatting.RED));
				return;
			}
			ClientPlayNetworking.send(new ExpeditionDisbandPayload());
			onClose();
		}).bounds(panelX, buttonY, 130, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.villagerciv.expedition.done"), _ -> onClose())
				.bounds(panelX + 134, buttonY, 94, 20).build());
		addRenderableWidget(Button.builder(Component.literal("?"), _ -> {
		}).bounds(panelX + panelWidth - 20, buttonY, 20, 20)
				.tooltip(Tooltip.create(Component.translatable("screen.villagerciv.expedition.feed_hint"))).build());
	}

	@Override
	protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = panelX + PADDING;
		int members = Math.max(1, info.members().size());
		centredLabel(graphics, title, panelY + PADDING, TEXT);
		centredLabel(graphics, Component.translatable(info.following()
				? "screen.villagerciv.expedition.following" : "screen.villagerciv.expedition.camped"),
				panelY + PADDING + 11, info.following() ? MUTED : GOLD);

		slot(graphics, FOOD_ICON, left, foodY);
		if (info.starving()) {
			label(graphics, Component.translatable("screen.villagerciv.expedition.starving"), left + SLOT_SIZE + 6, foodY + 5, BAD);
		} else {
			int days = info.food() / members;
			label(graphics, Component.translatable("screen.villagerciv.expedition.food", info.food(), days),
					left + SLOT_SIZE + 6, foodY + 5, days < 2 ? GOLD : TEXT);
		}
		if (isOver(mouseX, mouseY, left, foodY, SLOT_SIZE, SLOT_SIZE)) {
			tooltip(graphics, List.of(Component.translatable("commodity.villagerciv.food"),
					Component.translatable("screen.villagerciv.expedition.food_tooltip").withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
		}

		memberTable(graphics, info.members(), 0, info.members().size(), tableY,
				Component.translatable("screen.villagerciv.expedition.column.role"));
	}
}
