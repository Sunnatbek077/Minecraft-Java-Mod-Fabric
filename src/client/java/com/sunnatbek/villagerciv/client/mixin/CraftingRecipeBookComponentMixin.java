package com.sunnatbek.villagerciv.client.mixin;

import com.sunnatbek.villagerciv.mod.ModContent;
import net.minecraft.client.gui.screens.recipebook.CraftingRecipeBookComponent;
import net.minecraft.client.gui.screens.recipebook.RecipeBookComponent;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Adds the mod's page to the crafting table's recipe book, as the last tab down the side. */
@Mixin(CraftingRecipeBookComponent.class)
public abstract class CraftingRecipeBookComponentMixin {
	@Shadow
	@Final
	@Mutable
	private static List<RecipeBookComponent.TabInfo> TABS;

	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void villagerciv$addTab(CallbackInfo callback) {
		List<RecipeBookComponent.TabInfo> tabs = new ArrayList<>(TABS);
		tabs.add(new RecipeBookComponent.TabInfo(new ItemStack(ModContent.FOUNDING_CHARTER), Optional.empty(), ModContent.RECIPE_PAGE));
		TABS = List.copyOf(tabs);
	}
}
