package com.sunnatbek.villagerciv.mixin;

import com.sunnatbek.villagerciv.mod.ModContent;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.NormalCraftingRecipe;
import net.minecraft.world.item.crafting.RecipeBookCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

/**
 * Puts the mod's recipes on a page of their own in the recipe book. Which page a crafting recipe
 * goes on is fixed by the game to one of four; a recipe is recognised as the mod's by the group
 * its file names, and sent to the mod's page instead.
 */
@Mixin(NormalCraftingRecipe.class)
public abstract class NormalCraftingRecipeMixin implements CraftingRecipe {
	@Shadow
	public abstract String group();

	@Override
	public RecipeBookCategory recipeBookCategory() {
		return group().startsWith(ModContent.RECIPE_GROUP) ? ModContent.RECIPE_PAGE : CraftingRecipe.super.recipeBookCategory();
	}
}
