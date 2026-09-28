import java.io.*; import java.util.*;
import dan200.computercraft.api.filesystem.IMount;
import dan200.computercraft.api.filesystem.IWritableMount;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.computer.IComputerEnvironment;
import dan200.computercraft.core.filesystem.FileMount;
import dan200.computercraft.core.filesystem.JarMount;
import dan200.computercraft.core.terminal.Terminal;

/**
 * Runs real ComputerCraft computers (the jar's own bios, ROM and LuaJ) without Minecraft.
 * args: jar workDir computers seconds units program
 *   program "compute": each timer event does <units> steps of arithmetic, then its API calls
 *                       (os.startTimer, and fs writes every 5 events), like a program that works
 *                       things out and then acts.
 *   program "dash":     each event does the same work split over 19 screen lines, with a
 *                       term.setCursorPos + term.write after each line, like a dashboard redraw.
 *                       Its API calls are spread through the event, so with -Dcc.threads it holds
 *                       the world lock for most of the event.
 * Every computer wants 20 events a second. At the end, every checksum is recomputed in Java.
 */
public class HeadlessCC {
  static String program(String kind) {
    String head = "local units = tonumber(({...})[1]) or 20000\nlocal per = math.floor(units / 19)\nlocal x, n = 0, 0\nlocal t = os.startTimer(0.05)\n"
      + "while true do\n  local e, id = os.pullEvent(\"timer\")\n  if id == t then\n";
    String body = kind.equals("dash")
      ? "    for line = 1, 19 do\n      for i = 1, per do x = (x * 31 + i) % 1000003 end\n      term.setCursorPos(1, line)\n      term.write(\"line \" .. line .. \" \" .. x)\n    end\n"
      : "    for line = 1, 19 do\n      for i = 1, per do x = (x * 31 + i) % 1000003 end\n    end\n";
    return head + body
      + "    n = n + 1\n    t = os.startTimer(0.05)\n"
      + "    if n % 5 == 0 then local f = fs.open(\"result\", \"w\") f.writeLine(n) f.writeLine(x) f.close() end\n"
      + "  end\nend\n";
  }

  public static void main(String[] a) throws Exception {
    try { run(a); } catch (Throwable t) { t.printStackTrace(); } finally { System.exit(0); }
  }

  static void run(String[] a) throws Exception {
    final File jar = new File(a[0]); final File work = new File(a[1]);
    int n = Integer.parseInt(a[2]); double seconds = Double.parseDouble(a[3]); int units = Integer.parseInt(a[4]);
    String kind = a.length > 5 ? a[5] : "compute";
    int per = units / 19;
    final boolean newIds = Boolean.getBoolean("harness.newIds");
    final int[] nextId = {newIds ? 0 : n};
    IComputerEnvironment env = new IComputerEnvironment() {
      public int getDay() { return 1; }
      public double getTimeOfDay() { return 6.0; }
      public boolean isColour() { return true; }
      public long getComputerSpaceLimit() { return 1000000L; }
      public int assignNewID() {
        // Deliberately slow read-then-write, like a counter file: overlapping calls give duplicates.
        int id = nextId[0];
        try { Thread.sleep(1); } catch (InterruptedException e) { }
        nextId[0] = id + 1;
        return id;
      }
      public IWritableMount createSaveDirMount(String sub, long cap) { return new FileMount(new File(work, sub), cap); }
      public IMount createResourceMount(String domain, String sub) {
        try { return new JarMount(jar, "assets/" + domain + "/" + sub); } catch (IOException e) { throw new RuntimeException(e); }
      }
    };
    Computer[] cs = new Computer[n];
    Terminal[] terms = new Terminal[n];
    for (int i = 0; i < n; i++) {
      File dir = new File(work, "computer/" + i); dir.mkdirs();
      write(new File(dir, "work"), program(kind));
      write(new File(dir, "startup"), "shell.run(\"work\", \"" + units + "\")\n");
      new File(dir, "result").delete();
      terms[i] = new Terminal(51, 19);
      cs[i] = new Computer(env, terms[i], newIds ? -1 : i);
      cs[i].turnOn();
    }
    long start = System.nanoTime(); long tick = 0;
    while ((System.nanoTime() - start) / 1e9 < seconds) {
      for (Computer c : cs) c.advance(0.05);
      tick++;
      long next = start + tick * 50000000L, now = System.nanoTime();
      if (next > now) Thread.sleep((next - now) / 1000000L, (int) ((next - now) % 1000000L));
    }
    double wall = (System.nanoTime() - start) / 1e9;
    int ok = 0, bad = 0, missing = 0; long events = 0; long minEv = Long.MAX_VALUE, maxEv = 0;
    for (int i = 0; i < n; i++) {
      File r = new File(work, "computer/" + i + "/result");
      if (!r.exists()) {
        missing++;
        System.out.println("MISSING computer " + i + ", isOn=" + cs[i].isOn() + ", screen:");
        for (int y = 0; y < terms[i].getHeight(); y++) {
          String l = terms[i].getLine(y).trim();
          if (l.length() > 0) System.out.println("  | " + l);
        }
        if (missing == 1) {
          for (Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
            StackTraceElement[] st = e.getValue();
            String name = e.getKey().getName();
            if (st.length == 0 || !(name.startsWith("Computer") || name.startsWith("Coroutine"))) continue;
            StringBuilder b = new StringBuilder("  THREAD " + name + " " + e.getKey().getState());
            for (int k = 0; k < Math.min(st.length, 14); k++) b.append("\n      at ").append(st[k]);
            System.out.println(b);
          }
        }
        continue;
      }
      BufferedReader br = new BufferedReader(new FileReader(r));
      long ev = (long) Double.parseDouble(br.readLine().trim()); double x = Double.parseDouble(br.readLine().trim()); br.close();
      double y = 0;
      for (long e = 0; e < ev; e++) for (int line = 0; line < 19; line++) for (int k = 1; k <= per; k++) { double v = y * 31 + k; y = v - 1000003.0 * Math.floor(v / 1000003.0); }
      if (y == x) ok++; else { bad++; System.out.println("computer " + i + ": checksum " + x + " expected " + y + " after " + ev + " events"); }
      events += ev; minEv = Math.min(minEv, ev); maxEv = Math.max(maxEv, ev);
    }
    if (newIds) {
      Set<Integer> seen = new TreeSet<Integer>(); int dupes = 0; StringBuilder ids = new StringBuilder();
      for (Computer c : cs) { ids.append(' ').append(c.getID()); if (!seen.add(c.getID())) dupes++; }
      System.out.println("IDS" + ids + " -> " + seen.size() + " unique, " + dupes + " DUPLICATES");
    }
    String mode = Integer.getInteger("cc.threads", 1) > 1 ? System.getProperty("cc.threads") + " threads" : Boolean.getBoolean("cc.reuseWorker") ? "reuse worker" : "original";
    System.out.println(String.format(Locale.ROOT, "RESULT|%s|%s|%d|%d|%.1f|%.2f|%d|%d|%d|%d|%d",
      mode, kind, n, units, events / wall, events / wall / n, minEv == Long.MAX_VALUE ? 0 : minEv, maxEv, ok, bad, missing));
  }
  static void write(File f, String s) throws IOException { Writer w = new FileWriter(f); w.write(s); w.close(); }
}
