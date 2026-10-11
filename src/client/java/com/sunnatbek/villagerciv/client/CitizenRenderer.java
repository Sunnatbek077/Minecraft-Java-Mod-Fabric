package com.sunnatbek.villagerciv.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.sunnatbek.villagerciv.mod.ModContent;
import com.sunnatbek.villagerciv.mod.entity.CitizenEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.ArmorModelSet;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.io.IOException;
import java.io.InputStream;

/**
 * Draws a citizen as a villager with arms and legs of its own. The vanilla villager is one
 * block of robe with its arms folded: it cannot wear a breastplate or hold a sword. This one has
 * a villager's head on a body shaped like a player's, so armour and whatever is in its hands are
 * drawn exactly as they are on a player.
 *
 * <p>The head is the game's own villager head, face and all: it is taken from the villager
 * texture when resources load (so a resource pack's villagers carry over too) and set on the
 * mod's own body skin.
 */
final class CitizenRenderer extends HumanoidMobRenderer<CitizenEntity, HumanoidRenderState, HumanoidModel<HumanoidRenderState>> {
	private static final Identifier BODY = ModContent.id("textures/entity/citizen.png");
	private static final Identifier VILLAGER = Identifier.withDefaultNamespace("textures/entity/villager/villager.png");
	private static final Identifier COMPOSED = ModContent.id("textures/entity/citizen_composed");
	/** The head and nose take up this corner of both textures. */
	private static final int HEAD_WIDTH = 32;
	private static final int HEAD_HEIGHT = 18;

	private final Identifier texture;

	CitizenRenderer(EntityRendererProvider.Context context) {
		super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.ZOMBIE_VILLAGER)), 0.5f);
		addLayer(new HumanoidArmorLayer<>(this,
				ArmorModelSet.bake(ModelLayers.ZOMBIE_VILLAGER_ARMOR, context.getModelSet(), HumanoidModel::new),
				context.getEquipmentRenderer()));
		texture = compose(context.getResourceManager());
	}

	/** The body skin with the villager's head set on it; just the body skin if that cannot be done. */
	private static Identifier compose(ResourceManager resources) {
		try (InputStream bodyStream = resources.open(BODY); InputStream villagerStream = resources.open(VILLAGER)) {
			NativeImage skin = NativeImage.read(bodyStream);
			try (NativeImage villager = NativeImage.read(villagerStream)) {
				if (villager.getWidth() != skin.getWidth() || villager.getHeight() != skin.getHeight()) {
					skin.close();
					return BODY; // a pack with villagers of another size: keep the mod's own head
				}
				for (int y = 0; y < HEAD_HEIGHT; y++) {
					for (int x = 0; x < HEAD_WIDTH; x++) {
						skin.setPixel(x, y, villager.getPixel(x, y));
					}
				}
			}
			Minecraft.getInstance().getTextureManager().register(COMPOSED, new DynamicTexture(() -> "villagerciv citizen", skin));
			return COMPOSED;
		} catch (IOException | RuntimeException e) {
			return BODY;
		}
	}

	/** A crossbow is drawn back while it is being loaded and levelled once it is. */
	@Override
	protected HumanoidModel.ArmPose getArmPose(CitizenEntity citizen, HumanoidArm arm) {
		ItemStack held = citizen.getItemHeldByArm(arm);
		if (held.is(Items.CROSSBOW) && arm == citizen.getMainArm()) {
			if (citizen.isUsingItem()) {
				return HumanoidModel.ArmPose.CROSSBOW_CHARGE;
			}
			if (CrossbowItem.isCharged(held)) {
				return HumanoidModel.ArmPose.CROSSBOW_HOLD;
			}
		}
		return super.getArmPose(citizen, arm);
	}

	@Override
	public HumanoidRenderState createRenderState() {
		return new HumanoidRenderState();
	}

	@Override
	public Identifier getTextureLocation(HumanoidRenderState state) {
		return texture;
	}
}
