package com.sunnatbek.villagerciv.client;

import com.sunnatbek.villagerciv.mod.net.FoundConfirmPayload;
import com.sunnatbek.villagerciv.mod.net.SiteReportPayload;
import com.sunnatbek.villagerciv.sim.SiteScore;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The Leader's opinion of a proposed site. The decision stays with the player. */
final class SiteReportScreen extends PanelScreen {
	private static final int PANEL_WIDTH = 256;
	private static final int ASPECTS = 6;
	private static final int BAR_WIDTH = 80;

	private final SiteReportPayload report;

	SiteReportScreen(SiteReportPayload report) {
		super(Component.translatable("screen.villagerciv.site.title"));
		this.report = report;
	}

	@Override
	protected void init() {
		// title, intro | the Leader's words (up to two lines) | overall quality | one row per aspect
		int body = report.score().canSettle() ? 22 + 6 + 10 + 8 + 6 + ASPECTS * ROW_HEIGHT : 22;
		layoutPanel(PANEL_WIDTH, PADDING + 10 + 12 + 4 + body + PADDING);

		int buttonY = panelY + panelHeight + 6;
		Button confirm = Button.builder(Component.translatable("screen.villagerciv.site.confirm"), _ -> {
			ClientPlayNetworking.send(new FoundConfirmPayload(report.site()));
			onClose();
		}).bounds(width / 2 - 114, buttonY, 110, 20).build();
		confirm.active = report.score().canSettle();
		addRenderableWidget(confirm);
		addRenderableWidget(Button.builder(Component.translatable("screen.villagerciv.site.cancel"), _ -> onClose())
				.bounds(width / 2 + 4, buttonY, 110, 20).build());
	}

	@Override
	protected void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
		SiteScore score = report.score();
		int left = panelX + PADDING;
		int right = panelX + panelWidth - PADDING;
		int y = panelY + PADDING;
		centredLabel(graphics, title, y, TEXT);
		centredLabel(graphics, Component.translatable("screen.villagerciv.site.intro", report.leaderName()), y + 11, MUTED);
		y += 10 + 12 + 4;

		if (!score.canSettle()) {
			wrappedCentred(graphics, Component.translatable("screen.villagerciv.site.problem."
					+ score.problem().name().toLowerCase(Locale.ROOT)), y, BAD);
			return;
		}
		String verdict = score.total() >= 70 ? "good" : score.total() >= 45 ? "fair" : "poor";
		int verdictColour = score.total() >= 70 ? GOOD : score.total() >= 45 ? GOLD : BAD;
		wrappedCentred(graphics, Component.translatable("screen.villagerciv.site." + verdict), y, verdictColour);
		y += 22 + 6;

		label(graphics, Component.translatable("screen.villagerciv.site.quality_label"), left, y, TEXT);
		rightLabel(graphics, Component.literal(score.total() + "/100"), right, y, verdictColour);
		y += 10;
		bar(graphics, left, y, panelWidth - 2 * PADDING, 6, score.total(), verdictColour);
		y += 8 + 6;

		y = aspect(graphics, "water", score.water(), y);
		y = aspect(graphics, "wood", score.wood(), y);
		y = aspect(graphics, "stone", score.stone(), y);
		y = aspect(graphics, "flatness", score.flatness(), y);
		y = aspect(graphics, "fertility", score.fertility(), y);
		aspect(graphics, "defense", score.defense(), y);
	}

	/** One row: what was looked at, a gauge, and the Leader's word for it. */
	private int aspect(GuiGraphicsExtractor graphics, String key, int value, int y) {
		int left = panelX + PADDING;
		int right = panelX + panelWidth - PADDING;
		int colour = value > 0 ? gradeColour(value) : BAD;
		String level = value >= 67 ? "plenty" : value >= 34 ? "some" : value > 0 ? "little" : "none";
		label(graphics, Component.translatable("screen.villagerciv.site." + key), left, y, TEXT);
		bar(graphics, left + 104, y + 1, BAR_WIDTH, 6, value, colour);
		rightLabel(graphics, Component.translatable("screen.villagerciv.site.level." + level), right, y, colour);
		return y + ROW_HEIGHT;
	}
}
