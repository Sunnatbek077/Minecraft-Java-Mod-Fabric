package com.sunnatbek.villagerciv.mod;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The top of the ground a settlement lives on. Under a castle the world's own heightmap gives the
 * top of the castle, a few hundred blocks up; what the town wants is the ground beneath the
 * platform, and that is what this gives. Everywhere else it is the heightmap.
 */
public final class Heights {
	private Heights() {
	}

	/** As {@link ServerLevel#getHeight(Heightmap.Types, int, int)}, but under a castle's platform rather than on top of it. */
	public static int top(ServerLevel level, Heightmap.Types type, int x, int z) {
		int top = level.getHeight(type, x, z);
		EmbodimentController embodiment = VillagerCivMod.embodiment();
		int ceiling = embodiment == null ? CastleWorks.NO_CEILING : embodiment.castles().ceilingAt(x, z);
		if (ceiling == CastleWorks.NO_CEILING || top <= ceiling) {
			return top;
		}
		BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos(x, ceiling - 1, z);
		for (; cursor.getY() >= level.getMinY(); cursor.move(0, -1, 0)) {
			if (type.isOpaque().test(level.getBlockState(cursor))) {
				return cursor.getY() + 1;
			}
		}
		return level.getMinY();
	}
}
