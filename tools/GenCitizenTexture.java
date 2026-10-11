import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

/**
 * Paints the citizen's skin: a villager with separate arms and legs, so that it can wear armour
 * and hold things the way a player does. 64x64, in the layout of the game's humanoid villager model.
 * Run: java tools/GenCitizenTexture.java <out.png>
 */
public class GenCitizenTexture {
	static BufferedImage img = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);

	static int shade(int rgb, int x, int y, int amount) {
		int n = ((x * 73856093) ^ (y * 19349663)) >>> 3;
		int d = (n % (2 * amount + 1)) - amount;
		int r = Math.clamp(((rgb >> 16) & 255) + d, 0, 255);
		int g = Math.clamp(((rgb >> 8) & 255) + d, 0, 255);
		int b = Math.clamp((rgb & 255) + d, 0, 255);
		return 0xFF000000 | (r << 16) | (g << 8) | b;
	}

	static void fill(int x0, int y0, int w, int h, int rgb) {
		for (int y = y0; y < y0 + h; y++) {
			for (int x = x0; x < x0 + w; x++) {
				img.setRGB(x, y, shade(rgb, x, y, 5));
			}
		}
	}

	static void px(int x, int y, int rgb) {
		img.setRGB(x, y, 0xFF000000 | rgb);
	}

	/** All six faces of a box of the given size whose texture starts at (u, v). */
	static void box(int u, int v, int w, int h, int d, int rgb) {
		fill(u + d, v, w, d, rgb);
		fill(u + d + w, v, w, d, rgb);
		fill(u, v + d, 2 * (w + d), h, rgb);
	}

	public static void main(String[] args) throws Exception {
		int skin = 0xBE8C72, skinDark = 0xA9785F, robe = 0x5C4031, robeDark = 0x4A3327, belt = 0x2F2119;
		int trousers = 0x4A4E59, boots = 0x2A1F19, hair = 0x3A2A20;

		// Head 8x10x8 at (0,0); its front face is x 8..15, y 8..17. Bald and plain, as a
		// villager's is, and painted flat: on so few pixels any mottling reads as a blemish.
		for (int y = 0; y < 18; y++) {
			for (int x = 0; x < 32; x++) {
				if (y >= 8 || (x >= 8 && x < 24)) {
					px(x, y, y < 8 && x >= 16 ? skinDark : skin);
				}
			}
		}
		for (int x = 0; x < 32; x++) {           // a little shade under the crown and at the jaw
			px(x, 8, 0xC59378);
			px(x, 17, skinDark);
		}
		int fx = 8, fy = 8;                       // top-left of the face
		for (int x = 1; x <= 6; x++) {           // the one long brow
			px(fx + x, fy + 3, 0x3A2A20);
		}
		px(fx + 1, fy + 4, 0xFFFFFF); px(fx + 2, fy + 4, 0x2F7A3A);   // eyes: white outside, green inside
		px(fx + 5, fy + 4, 0x2F7A3A); px(fx + 6, fy + 4, 0xFFFFFF);
		px(fx + 2, fy + 8, 0x8E5B45); px(fx + 3, fy + 8, 0x8E5B45);   // the mouth, mostly behind the nose
		px(fx + 4, fy + 8, 0x8E5B45); px(fx + 5, fy + 8, 0x8E5B45);
		// Nose 2x4x2 at (24,0): one plain shade, a touch darker beneath.
		for (int y = 0; y < 6; y++) {
			for (int x = 24; x < 32; x++) {
				px(x, y, y == 5 ? 0x9A6B54 : skinDark);
			}
		}

		// Body 8x12x6 at (16,20): a belted tunic. Its front face is x 22..29, y 26..37.
		box(16, 20, 8, 12, 6, robe);
		for (int x = 16; x < 44; x++) {
			px(x, 26 + 9, belt);                 // the belt
			px(x, 26 + 11, robeDark);            // the hem
		}
		for (int y = 26; y < 26 + 9; y++) {
			px(22 + 3, y, robeDark);              // the opening down the front
			px(22 + 4, y, robeDark);
		}
		px(22 + 3, 26 + 9, 0xC9A227);             // the buckle
		px(22 + 4, 26 + 9, 0xC9A227);
		// The long robe that a villager wears over all this (8x20x6 at (0,38)) is left clear:
		// it would hang to the ankles and hide the legs, and with them any leggings.

		// Arms 4x12x4 at (44,22): sleeves, with hands in the last three rows.
		box(44, 22, 4, 12, 4, robe);
		fill(44, 22 + 4 + 9, 16, 3, skin);
		fill(52, 22, 4, 4, skin);                // underside: the hand
		for (int x = 44; x < 60; x++) {
			px(x, 22 + 4 + 8, robeDark);         // the cuff
		}

		// Legs 4x12x4 at (0,22): trousers and boots.
		box(0, 22, 4, 12, 4, trousers);
		fill(0, 22 + 4 + 9, 16, 3, boots);
		fill(8, 22, 4, 4, boots);                // the sole

		ImageIO.write(img, "png", new File(args[0]));
	}
}
