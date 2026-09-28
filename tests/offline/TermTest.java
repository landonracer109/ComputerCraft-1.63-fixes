import dan200.computercraft.core.terminal.Terminal;
import net.minecraft.nbt.NBTTagCompound;
public class TermTest {
  static String rep(char c, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(c); return b.toString(); }
  static void check(Terminal t, String label) {
    for (int y = 0; y < t.getHeight(); y++) {
      if (t.getLine(y).length() != t.getWidth() || t.getColourLine(y).length() != 2 * t.getWidth())
        throw new RuntimeException(label + ": bad lengths on row " + y);
    }
    System.out.println("PASS " + label + " (" + t.getWidth() + "x" + t.getHeight() + ")");
  }
  public static void main(String[] a) {
    // 1: server sends lines/colour lines of the wrong width (the 12:07 renderer crash case)
    Terminal t = new Terminal(82, 5);
    NBTTagCompound n = new NBTTagCompound();
    for (int i = 0; i < 5; i++) { n.func_74778_a("term_line_" + i, rep('x', 41)); n.func_74778_a("term_colourline_" + i, rep('0', 41) + rep('f', 41)); }
    n.func_74768_a("term_textColour", 0); n.func_74768_a("term_bgColour", 15);
    t.readFromNBT(n);
    check(t, "readFromNBT with 41-wide data into 82-wide terminal");
    if (!t.getColourLine(0).substring(82, 123).equals(rep('f', 41))) throw new RuntimeException("background colours not preserved");
    // 2: resize with mismatched old data (the 2:54 / 3:04 crash case): original code threw index 82
    t.resize(41, 5); check(t, "resize 82->41");
    t.resize(100, 8); check(t, "resize 41->100 taller");
    // 3: normal data still round-trips untouched
    Terminal ok = new Terminal(10, 2); ok.setCursorPos(0, 0); ok.write("hello");
    NBTTagCompound m = new NBTTagCompound(); ok.writeToNBT(m);
    Terminal ok2 = new Terminal(10, 2); ok2.readFromNBT(m);
    if (!ok2.getLine(0).equals("hello     ") || !ok2.getColourLine(0).equals(ok.getColourLine(0))) throw new RuntimeException("round trip changed data");
    check(ok2, "normal round trip unchanged");
    ok2.write("x"); ok2.scroll(1); ok2.resize(5, 3); check(ok2, "write/scroll/resize after normal load");
  }
}
