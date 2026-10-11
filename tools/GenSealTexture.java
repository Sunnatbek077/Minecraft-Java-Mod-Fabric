import java.awt.image.BufferedImage; import java.io.File; import javax.imageio.ImageIO;
/** A round gold seal with a green stone set in it and a red ribbon: the Surveyor's Seal, 16x16. */
public class GenSealTexture { public static void main(String[] a) throws Exception {
 BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
 int gold=0xF2C230, goldDark=0xB8860B, goldLight=0xFFE680, green=0x2FAE5A, greenLight=0x7CE39A, red=0xB02A2A, redDark=0x7A1C1C, edge=0x5A4208;
 for (int y=0;y<16;y++) for (int x=0;x<16;x++) {
   double dx=x-7.5, dy=y-6.5, d=Math.sqrt(dx*dx+dy*dy); int c=-1;
   if (d<=6.2) c = d>5.2 ? edge : d>4.2 ? ((x+y)%2==0?goldDark:gold) : d>2.4 ? (dx+dy<0?goldLight:gold) : d>1.2 ? green : greenLight;
   if (c<0 && y>=10 && y<=15) { if ((x>=4&&x<=6 && y-10<=x-1+6) ) c = (x==4||y==15)?redDark:red; if (x>=9&&x<=11) c = (x==11||y==15)?redDark:red; }
   if (c>=0) img.setRGB(x,y,0xFF000000|c);
 }
 // notch the ribbon ends
 img.setRGB(5,15,0); img.setRGB(10,15,0);
 ImageIO.write(img,"png",new File(a[0])); } }
