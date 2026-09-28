import dan200.computercraft.core.terminal.Terminal;
import net.minecraft.nbt.NBTTagCompound;
// One thread resizes/scrolls/writes a terminal like a computer does; the main thread saves it with
// writeToNBT like the server does. Reports the first failure, or PASS after the given seconds.
public class RaceTest {
  static volatile Throwable fail;
  public static void main(String[] a) throws Exception {
    final Terminal t = new Terminal(40, 20);
    final long end = System.currentTimeMillis() + Long.parseLong(a[0]) * 1000;
    Thread computer = new Thread(new Runnable() { public void run() {
      int i = 0;
      try {
        while (System.currentTimeMillis() < end && fail == null) {
          i++;
          t.resize(20 + i % 60, 5 + i % 30);
          t.setCursorPos(1, i % 5); t.write("hello " + i); t.scroll(1); t.clear();
        }
      } catch (Throwable e) { fail = e; }
    }});
    computer.start();
    long saves = 0;
    try {
      while (System.currentTimeMillis() < end && fail == null) {
        NBTTagCompound n = new NBTTagCompound(); t.writeToNBT(n); saves++;
      }
    } catch (Throwable e) { if (fail == null) fail = e; }
    computer.join();
    if (fail != null) { System.out.println("FAIL after " + saves + " saves: " + fail); System.exit(1); }
    System.out.println("PASS " + saves + " saves, no errors");
  }
}
