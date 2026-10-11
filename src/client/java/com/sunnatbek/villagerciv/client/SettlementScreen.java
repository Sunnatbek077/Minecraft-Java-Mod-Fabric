package com.sunnatbek.villagerciv.client;

import com.sunnatbek.villagerciv.mod.net.SettlementInfoPayload;
import com.sunnatbek.villagerciv.sim.Commodity;
import com.sunnatbek.villagerciv.sim.Policy;
import com.sunnatbek.villagerciv.sim.StockStatus;
import com.sunnatbek.villagerciv.sim.ToolKind;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What a settlement is and has, as a vanilla-style window: stockpile and tools as item slots with
 * tooltips, citizens as a table. Read-only: the player watches and helps, but does not rule.
 */
final class SettlementScreen extends PanelScreen {
	private static final int PANEL_WIDTH = 256;
	private static final int ROWS_PER_PAGE = 8;
	private static final int PROJECT_HEIGHT = 10 + 8 + 4 + ROW_HEIGHT + 4;
	private static final int FIXED_HEIGHT = PADDING + 10 + 12 + 11 + 6 + (10 + SLOT_SIZE + 6) * 2 + PROJECT_HEIGHT + 10 + ROW_HEIGHT + 6 + 10 + PADDING;

	// One icon per commodity and per tool kind, in enum order.
	private static final ItemStack[] COMMODITY_ICONS = {
			new ItemStack(Items.BREAD), new ItemStack(Items.OAK_LOG), new ItemStack(Items.COBBLESTONE),
			new ItemStack(Items.RAW_IRON), new ItemStack(Items.IRON_PICKAXE)
	};
	private static final ItemStack[] TOOL_ICONS = {
			new ItemStack(Items.IRON_HOE), new ItemStack(Items.IRON_AXE), new ItemStack(Items.IRON_PICKAXE),
			new ItemStack(Items.IRON_SHOVEL), new ItemStack(Items.IRON_SWORD)
	};

	private final SettlementInfoPayload info;
	private int page;
	private int stockY;
	private int toolsY;
	private int projectY;
	private int requestsY;
	private int tableY;
	private int footerY;

	SettlementScreen(SettlementInfoPayload info) {
		super(Component.literal(info.name()));
		this.info = info;
	}

	private int requestsHeight() {
		return info.requests().isEmpty() ? 0 : 10 + info.requests().size() * ROW_HEIGHT + 6;
	}

	/** Citizens shown at once: as many as fit on the screen, up to {@link #ROWS_PER_PAGE}. */
	private int rowsPerPage() {
		int room = (height - BUTTON_AREA - 8 - FIXED_HEIGHT - requestsHeight()) / ROW_HEIGHT;
		return Math.clamp(room, 3, ROWS_PER_PAGE);
	}

	private int pages() {
		return Math.max(1, (info.members().size() + rowsPerPage() - 1) / rowsPerPage());
	}

	@Override
	protected void init() {
		int rows = Math.min(rowsPerPage(), Math.max(1, info.members().size()));
		// title, subtitle | stockpile | tools | building | requests | citizens | footer
		layoutPanel(PANEL_WIDTH, FIXED_HEIGHT + requestsHeight() + rows * ROW_HEIGHT);
		page = Math.min(page, pages() - 1);
		stockY = panelY + PADDING + 10 + 12 + 11 + 6;
		toolsY = stockY + 10 + SLOT_SIZE + 6;
		projectY = toolsY + 10 + SLOT_SIZE + 6;
		requestsY = projectY + PROJECT_HEIGHT;
		tableY = requestsY + requestsHeight();
		footerY = tableY + 10 + ROW_HEIGHT + rows * ROW_HEIGHT + 6;

		int buttonY = panelY + panelHeight + 6;
		addRenderableWidget(Button.builder(Component.literal("?"), _ -> {
		}).bounds(panelX + panelWidth - 20 - 4, buttonY, 20, 20)
				.tooltip(Tooltip.create(Component.translatable("screen.villagerciv.settlement.give_hint"))).build());
		addRenderableWidget(Button.builder(Component.translatable("screen.villagerciv.expedition.done"), _ -> onClose())
				.bounds(width / 2 - 60, buttonY, 120, 20).build());
		if (pages() > 1) {
			addRenderableWidget(Button.builder(Component.literal("<"), _ -> page = Math.floorMod(page - 1, pages()))
					.bounds(panelX + 4, buttonY, 20, 20).build());
			addRenderableWidget(Button.builder(Component.literal(">"), _ -> page = Math.floorMod(page + 1, pages()))
					.bounds(panelX + 26, buttonY, 20, 20).build());
		}
	}

	@Override
	protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		int left = panelX + PADDING;
		int right = panelX + panelWidth - PADDING;
		int citizens = info.members().size();

		centredLabel(graphics, title, panelY + PADDING, TEXT);
		centredLabel(graphics, Component.translatable("screen.villagerciv.settlement.summary",
				Component.translatable("stage.villagerciv." + info.stage().name().toLowerCase(Locale.ROOT)), citizens,
				info.beds()),
				panelY + PADDING + 11, info.beds() < citizens ? GOLD : MUTED);
		if (info.patron()) {
			rightLabel(graphics, Component.translatable("screen.villagerciv.settlement.patron_badge"), right, panelY + PADDING, GOLD);
		}
		// The plans get a line of their own: with all three there is a lot to say.
		MutableComponent names = Component.empty();
		List<Component> hints = new ArrayList<>();
		for (Policy plan : Policy.values()) {
			if (plan != Policy.NONE && info.hasPlan(plan)) {
				String key = "policy.villagerciv." + plan.name().toLowerCase(Locale.ROOT);
				if (!hints.isEmpty()) {
					names.append(", ");
				}
				names.append(Component.translatable(key));
				hints.add(Component.translatable(key).append(": ").append(Component.translatable(key + ".hint").withStyle(ChatFormatting.GRAY)));
			}
		}
		boolean none = hints.isEmpty();
		Component plans = none ? Component.translatable("screen.villagerciv.settlement.no_plan")
				: Component.translatable("screen.villagerciv.settlement.plan", names);
		int plansY = panelY + PADDING + 22;
		centredLabel(graphics, plans, plansY, none ? BAD : GOLD);
		if (isOver(mouseX, mouseY, left, plansY, panelWidth - 2 * PADDING, 9)) {
			tooltip(graphics, none ? List.of(Component.translatable("policy.villagerciv.none.hint")) : hints, mouseX, mouseY);
		}

		// --- Stockpile ---
		label(graphics, Component.translatable("screen.villagerciv.settlement.stockpile"), left, stockY, TEXT);
		int cell = (panelWidth - 2 * PADDING) / Commodity.values().length;
		for (Commodity commodity : Commodity.values()) {
			int x = left + commodity.ordinal() * cell;
			int y = stockY + 10;
			int amount = info.stock()[commodity.ordinal()];
			slot(graphics, COMMODITY_ICONS[commodity.ordinal()], x, y);
			StockStatus status = StockStatus.values()[info.statuses()[commodity.ordinal()]];
			label(graphics, Component.literal(Integer.toString(amount)), x + SLOT_SIZE + 3, y + 5,
					status == StockStatus.CRITICAL ? BAD : status == StockStatus.LOW ? GOLD : amount > 0 ? TEXT : MUTED);
			if (isOver(mouseX, mouseY, x, y, cell - 2, SLOT_SIZE)) {
				Component name = Component.translatable("commodity.villagerciv." + commodity.name().toLowerCase(Locale.ROOT));
				Component detail = commodity == Commodity.FOOD
						? Component.translatable("screen.villagerciv.settlement.food_days", amount / Math.max(1, citizens))
						: Component.translatable("screen.villagerciv.settlement.commodity_hint."
								+ commodity.name().toLowerCase(Locale.ROOT));
				List<Component> lines = new ArrayList<>(List.of(name.copy().append(": " + amount), detail.copy().withStyle(ChatFormatting.GRAY)));
				if (status != StockStatus.OK) {
					lines.add(Component.translatable("screen.villagerciv.settlement.status." + status.name().toLowerCase(Locale.ROOT))
							.withStyle(status == StockStatus.CRITICAL ? ChatFormatting.RED : ChatFormatting.GOLD));
				}
				tooltip(graphics, lines, mouseX, mouseY);
			}
		}

		// --- Tools ---
		label(graphics, Component.translatable("screen.villagerciv.settlement.tools"), left, toolsY, TEXT);
		rightLabel(graphics, Component.translatable("screen.villagerciv.settlement.tools_legend"), right, toolsY, MUTED);
		for (ToolKind kind : ToolKind.values()) {
			int x = left + kind.ordinal() * cell;
			int y = toolsY + 10;
			int have = info.toolsHave()[kind.ordinal()];
			int needed = info.toolsNeeded()[kind.ordinal()];
			slot(graphics, TOOL_ICONS[kind.ordinal()], x, y);
			label(graphics, Component.literal(have + "/" + needed), x + SLOT_SIZE + 3, y + 5,
					needed == 0 ? MUTED : have >= needed ? GOOD : BAD);
			if (isOver(mouseX, mouseY, x, y, cell - 2, SLOT_SIZE)) {
				String key = kind.name().toLowerCase(Locale.ROOT);
				Component status = needed == 0
						? Component.translatable("screen.villagerciv.settlement.tool_unused").withStyle(ChatFormatting.GRAY)
						: have >= needed
								? Component.translatable("screen.villagerciv.settlement.tool_enough").withStyle(ChatFormatting.GREEN)
								: Component.translatable("screen.villagerciv.settlement.tool_short", needed - have).withStyle(ChatFormatting.RED);
				tooltip(graphics, List.of(Component.translatable("tool.villagerciv." + key),
						Component.translatable("screen.villagerciv.settlement.tool_use." + key).withStyle(ChatFormatting.GRAY),
						Component.translatable("screen.villagerciv.settlement.tool_count", have, needed), status), mouseX, mouseY);
			}
		}

		// --- Building ---
		label(graphics, Component.translatable("screen.villagerciv.settlement.building"), left, projectY, TEXT);
		switch (info.building()) {
			case BUILDING -> {
				rightLabel(graphics, Component.literal(info.project() + "  " + info.projectPercent() + "%"), right, projectY, GOOD);
				bar(graphics, left, projectY + 10, panelWidth - 2 * PADDING, 6, info.projectPercent(), GOOD);
			}
			case WAITING_FOR_MATERIALS -> {
				rightLabel(graphics, Component.translatable("screen.villagerciv.settlement.building.waiting", info.project()),
						right, projectY, BAD);
				// What it takes against what there is, so it is plain what to bring or wait for.
				label(graphics, Component.translatable("screen.villagerciv.settlement.building.needs",
						need(Commodity.WOOD, info.needWood()), need(Commodity.STONE, info.needStone())), left, projectY + 10, MUTED);
			}
			case PLANNING -> rightLabel(graphics,
					Component.translatable("screen.villagerciv.settlement.building.planning", info.project()), right, projectY, GOLD);
			case NOTHING_TO_BUILD -> rightLabel(graphics,
					Component.translatable("screen.villagerciv.settlement.building.nothing"), right, projectY, MUTED);
			case NO_ROOM -> {
				rightLabel(graphics, Component.translatable("screen.villagerciv.settlement.building.no_room"), right, projectY, GOLD);
				label(graphics, Component.translatable("screen.villagerciv.settlement.building.no_room.detail"), left, projectY + 10, MUTED);
			}
		}

		// --- Defence: how much danger, who stands guard, and how far the wall has come ---
		int defenceY = projectY + 10 + 8 + 4;
		label(graphics, Component.translatable("screen.villagerciv.settlement.defence"), left, defenceY, TEXT);
		String mood = info.threat() == 0 ? "calm" : info.threat() < 5 ? "uneasy" : info.threat() < 10 ? "threatened" : "attacked";
		Component defence = Component.translatable("screen.villagerciv.settlement.defence." + mood);
		defence = info.wallTotal() > 0
				? Component.translatable("screen.villagerciv.settlement.defence.with_wall", defence, info.guards(), info.wallBuilt(), info.wallTotal())
				: Component.translatable("screen.villagerciv.settlement.defence.no_wall", defence, info.guards());
		rightLabel(graphics, defence, right, defenceY, info.threat() >= 10 ? BAD : info.threat() >= 5 ? GOLD : MUTED);

		// --- Requests: what the settlement asks of its patron, and how far each has been met ---
		if (!info.requests().isEmpty()) {
			label(graphics, Component.translatable("screen.villagerciv.settlement.requests"), left, requestsY, GOLD);
			int requestY = requestsY + 10;
			for (SettlementInfoPayload.RequestLine request : info.requests()) {
				label(graphics, Component.translatable("screen.villagerciv.settlement.request",
						Component.translatable("commodity.villagerciv." + request.commodity().name().toLowerCase(Locale.ROOT)),
						request.delivered(), request.amount()), left, requestY, TEXT);
				rightLabel(graphics, request.purpose().isEmpty()
						? Component.translatable("screen.villagerciv.settlement.request.for_food", request.daysLeft())
						: Component.translatable("screen.villagerciv.settlement.request.for_building", request.purpose(), request.daysLeft()),
						right, requestY, request.daysLeft() <= 1 ? BAD : MUTED);
				requestY += ROW_HEIGHT;
			}
		}

		// --- Citizens ---
		label(graphics, Component.translatable("screen.villagerciv.settlement.citizens"), left, tableY, TEXT);
		if (pages() > 1) {
			rightLabel(graphics, Component.literal((page + 1) + "/" + pages()), right, tableY, MUTED);
		}
		memberTable(graphics, info.members(), page * rowsPerPage(), (page + 1) * rowsPerPage(), tableY + 10,
				Component.translatable("screen.villagerciv.settlement.column.work"));

		// --- Footer ---
		divider(graphics, footerY - 3);
		label(graphics, Component.translatable("screen.villagerciv.settlement.land", info.territoryChunks(), info.siteQuality(),
				info.morale()), left, footerY, info.morale() < 40 ? BAD : MUTED);
		rightLabel(graphics, Component.translatable("screen.villagerciv.settlement.reputation", info.reputation()), right, footerY,
				info.reputation() > 0 ? GOLD : MUTED);
	}


	/** "240 wood (have 82)", red while there is too little and green once there is enough. */
	private Component need(Commodity commodity, int needed) {
		int have = info.stock()[commodity.ordinal()];
		return Component.translatable("screen.villagerciv.settlement.building.need", needed,
				Component.translatable("commodity.villagerciv." + commodity.name().toLowerCase(Locale.ROOT)), have)
				.withStyle(have >= needed ? ChatFormatting.GREEN : ChatFormatting.RED);
	}
}
