package com.sunnatbek.villagerciv.client;

import com.sunnatbek.villagerciv.mod.net.ExpeditionInfoPayload;
import com.sunnatbek.villagerciv.sim.Activity;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * A screen laid out as a vanilla-style window: the game's dark popup panel, its text colours and
 * the item slots that containers use, so the mod's screens read like part of the game.
 */
abstract class PanelScreen extends Screen {
	private static final Identifier PANEL = Identifier.withDefaultNamespace("popup/background");
	private static final Identifier SLOT = Identifier.withDefaultNamespace("container/slot");
	private static final Identifier HEART_CONTAINER = Identifier.withDefaultNamespace("hud/heart/container");
	private static final Identifier HEART_FULL = Identifier.withDefaultNamespace("hud/heart/full");
	private static final Identifier HEART_HALF = Identifier.withDefaultNamespace("hud/heart/half");
	private static final int LOW_LOYALTY = 30;
	protected static final int ROW_HEIGHT = 11;
	protected static final int BAR_BACKGROUND = 0xFF1A1A1A;

	/** Text colours for the dark vanilla popup panel: the game's own white, grey, green, red and gold. */
	protected static final int TEXT = 0xFFFFFFFF;
	protected static final int MUTED = 0xFFAAAAAA;
	protected static final int GOOD = 0xFF55FF55;
	protected static final int BAD = 0xFFFF5555;
	protected static final int GOLD = 0xFFFFAA00;
	protected static final int DIVIDER = 0xFF6A6A6A;

	protected static final int PADDING = 8;
	protected static final int SLOT_SIZE = 18;
	/** Room kept under the panel for its buttons. */
	protected static final int BUTTON_AREA = 28;

	protected int panelX;
	protected int panelY;
	protected int panelWidth;
	protected int panelHeight;

	protected PanelScreen(Component title) {
		super(title);
	}

	/** Centres a panel of the given size, leaving room for a row of buttons below it. */
	protected void layoutPanel(int panelWidth, int panelHeight) {
		this.panelWidth = panelWidth;
		this.panelHeight = panelHeight;
		this.panelX = (width - panelWidth) / 2;
		this.panelY = Math.max(4, (height - panelHeight - BUTTON_AREA) / 2);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		super.extractBackground(graphics, mouseX, mouseY, partialTick);
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PANEL, panelX, panelY, panelWidth, panelHeight);
		extractPanel(graphics, mouseX, mouseY);
	}

	/** Draws the panel's contents. Widgets are drawn afterwards, on top. */
	protected abstract void extractPanel(GuiGraphicsExtractor graphics, int mouseX, int mouseY);

	protected void label(GuiGraphicsExtractor graphics, Component text, int x, int y, int colour) {
		graphics.text(font, text, x, y, colour, true);
	}

	protected void centredLabel(GuiGraphicsExtractor graphics, Component text, int y, int colour) {
		graphics.text(font, text, panelX + (panelWidth - font.width(text)) / 2, y, colour, true);
	}

	protected void rightLabel(GuiGraphicsExtractor graphics, Component text, int rightX, int y, int colour) {
		graphics.text(font, text, rightX - font.width(text), y, colour, true);
	}

	protected void divider(GuiGraphicsExtractor graphics, int y) {
		graphics.fill(panelX + PADDING, y, panelX + panelWidth - PADDING, y + 1, DIVIDER);
	}

	/** An inventory slot holding an item as an icon. */
	protected void slot(GuiGraphicsExtractor graphics, ItemStack icon, int x, int y) {
		graphics.blitSprite(RenderPipelines.GUI_TEXTURED, SLOT, x, y, SLOT_SIZE, SLOT_SIZE);
		graphics.fakeItem(icon, x + 1, y + 1);
	}

	/** Draws text wrapped to a width and returns the y just below it. */
	protected int wrapped(GuiGraphicsExtractor graphics, Component text, int x, int y, int maxWidth, int colour) {
		for (FormattedCharSequence line : font.split(text, maxWidth)) {
			graphics.text(font, line, x, y, colour, true);
			y += font.lineHeight + 1;
		}
		return y;
	}

	/** Like {@link #wrapped}, with every line centred in the panel. */
	protected int wrappedCentred(GuiGraphicsExtractor graphics, Component text, int y, int colour) {
		for (FormattedCharSequence line : font.split(text, panelWidth - 2 * PADDING)) {
			graphics.text(font, line, panelX + (panelWidth - font.width(line)) / 2, y, colour, true);
			y += font.lineHeight + 1;
		}
		return y;
	}

	/** A horizontal gauge filled to {@code percent}. */
	protected void bar(GuiGraphicsExtractor graphics, int x, int y, int barWidth, int barHeight, int percent, int colour) {
		graphics.fill(x, y, x + barWidth, y + barHeight, BAR_BACKGROUND);
		int filled = (barWidth - 2) * Math.clamp(percent, 0, 100) / 100;
		graphics.fill(x + 1, y + 1, x + 1 + filled, y + barHeight - 1, colour);
	}

	/** Green, gold or red for a 0–100 value: plenty, some, little. */
	protected static int gradeColour(int percent) {
		return percent >= 67 ? GOOD : percent >= 34 ? GOLD : BAD;
	}

	/** Health out of 20 as five hearts, each worth four points. */
	protected void hearts(GuiGraphicsExtractor graphics, int health, int x, int y) {
		for (int i = 0; i < 5; i++) {
			int heartX = x + i * 8;
			graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_CONTAINER, heartX, y, 9, 9);
			int points = health - i * 4;
			if (points >= 3) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_FULL, heartX, y, 9, 9);
			} else if (points >= 1) {
				graphics.blitSprite(RenderPipelines.GUI_TEXTURED, HEART_HALF, heartX, y, 9, 9);
			}
		}
	}

	/** Height of {@link #memberTable} for a number of rows. */
	protected static int memberTableHeight(int rows) {
		return ROW_HEIGHT + rows * ROW_HEIGHT;
	}

	/**
	 * A table of citizens: name, role or work, health and loyalty.
	 *
	 * @param from first member to show; {@code to} is exclusive
	 */
	protected void memberTable(GuiGraphicsExtractor graphics, List<ExpeditionInfoPayload.Member> members, int from, int to, int y,
			Component roleHeader) {
		int left = panelX + PADDING;
		int right = panelX + panelWidth - PADDING;
		int roleX = left + 50;
		int doingX = left + 112;
		int healthX = left + 162;
		label(graphics, Component.translatable("screen.villagerciv.settlement.column.name"), left, y, MUTED);
		label(graphics, roleHeader, roleX, y, MUTED);
		label(graphics, Component.translatable("screen.villagerciv.settlement.column.doing"), doingX, y, MUTED);
		label(graphics, Component.translatable("screen.villagerciv.settlement.column.health"), healthX, y, MUTED);
		rightLabel(graphics, Component.translatable("screen.villagerciv.settlement.column.loyalty"), right, y, MUTED);
		divider(graphics, y + 9);

		int rowY = y + ROW_HEIGHT;
		for (int i = from; i < Math.min(members.size(), to); i++, rowY += ROW_HEIGHT) {
			ExpeditionInfoPayload.Member member = members.get(i);
			label(graphics, Component.literal(member.name()), left, rowY, TEXT);
			label(graphics, Component.translatable("role.villagerciv." + member.role().name().toLowerCase(Locale.ROOT)), roleX, rowY, TEXT);
			Activity activity = member.activity();
			label(graphics, Component.translatable("activity.villagerciv." + activity.name().toLowerCase(Locale.ROOT)), doingX, rowY,
					activity == Activity.IDLE || activity == Activity.WAITING ? GOLD
							: activity == Activity.SLEEPING || activity == Activity.RESTING ? MUTED : GOOD);
			hearts(graphics, member.health(), healthX, rowY - 1);
			rightLabel(graphics, Component.literal(Integer.toString(member.loyalty())), right, rowY,
					member.loyalty() < LOW_LOYALTY ? BAD : TEXT);
		}
	}

	protected static boolean isOver(int mouseX, int mouseY, int x, int y, int width, int height) {
		return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
	}

	protected void tooltip(GuiGraphicsExtractor graphics, List<Component> lines, int mouseX, int mouseY) {
		graphics.setComponentTooltipForNextFrame(font, lines, mouseX, mouseY);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}
}
