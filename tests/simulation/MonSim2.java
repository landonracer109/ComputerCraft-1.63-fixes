import java.util.*;
// Scenarios on top of MonSim's transcribed logic:
//  mixed:  random interleaved place/break on a 6x6 area
//  paste:  a full W x H wall "pasted" with its saved indices (no expand), with one or more blocks
//          missing from the paste, then random breaks (and some places)
public class MonSim2 {
  static List<int[]> ops; static List<int[]> pasted;
  static String replay(boolean patched) {
    MonSim.Mon.loops = 0; MonSim.PATCHED = patched; MonSim.grid = new MonSim.Mon[MonSim.N][MonSim.N];
    try {
      for (int[] p : pasted) { MonSim.Mon m = new MonSim.Mon(p[0], p[1]); m.xi = p[2]; m.yi = p[3]; m.w = p[4]; m.h = p[5]; MonSim.grid[p[0]][p[1]] = m; }
      for (int[] op : ops) { if (op[0] == 1) { if (MonSim.grid[op[1]][op[2]] == null) MonSim.place(op[1], op[2]); } else if (MonSim.grid[op[1]][op[2]] != null) MonSim.brk(op[1], op[2]); }
      return null;
    } catch (NullPointerException e) { return "NPE"; } catch (IllegalStateException e) { return "LOOP"; }
  }
  static String desc() {
    StringBuilder b = new StringBuilder();
    if (!pasted.isEmpty()) { int W = pasted.get(0)[4], H = pasted.get(0)[5]; b.append("pasted ").append(W).append('x').append(H).append(" wall, missing:");
      for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) { boolean has = false; for (int[] p : pasted) if (p[0] == x && p[1] == y) has = true; if (!has) b.append(" (").append(x).append(',').append(y).append(')'); } b.append(" |"); }
    for (int[] o : ops) b.append(o[0] == 1 ? " place(" : " BREAK(").append(o[1]).append(',').append(o[2]).append(')');
    return b.toString();
  }
  public static void main(String[] a) {
    Random r = new Random(7); int trials = Integer.parseInt(a[1]); boolean paste = a[0].equals("paste");
    int oc = 0, pc = 0, pBad = 0; String best = null; int bestLen = 99;
    for (int t = 0; t < trials; t++) {
      ops = new ArrayList<int[]>(); pasted = new ArrayList<int[]>();
      int W = 2 + r.nextInt(4), H = 2 + r.nextInt(4);
      if (paste) {
        List<int[]> cells = new ArrayList<int[]>();
        for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) cells.add(new int[]{x, y, x, y, W, H});
        Collections.shuffle(cells, r); int missing = 1 + r.nextInt(2);
        pasted.addAll(cells.subList(missing, cells.size()));
        int n = 1 + r.nextInt(6); for (int i = 0; i < n; i++) ops.add(new int[]{r.nextInt(5) == 0 ? 1 : 0, r.nextInt(W), r.nextInt(H)});
      } else {
        int n = 4 + r.nextInt(30); for (int i = 0; i < n; i++) ops.add(new int[]{r.nextInt(3) == 0 ? 0 : 1, r.nextInt(6), r.nextInt(6)});
      }
      String e1 = Boolean.getBoolean("v1hunt") ? replay(true) : replay(false);
      if (e1 != null) { oc++;
        // shortest prefix that still crashes
        List<int[]> all = ops; for (int k = 1; k <= all.size(); k++) { ops = new ArrayList<int[]>(all.subList(0, k)); if ((Boolean.getBoolean("v1hunt") ? replay(true) : replay(false)) != null) break; }
        String d = desc(); int len = ops.size() * 10 + pasted.size(); if (len < bestLen) { bestLen = len; best = d; } ops = all; }
      String e2 = replay(true); if (e2 != null) pc++; else if (MonSim.inconsistent() > 0) pBad++;
    }
    System.out.println(a[0] + ": " + trials + " trials, original crashed " + oc + ", patched crashed " + pc + ", patched runs left inconsistent " + pBad);
    if (best != null) System.out.println("  simplest crash: " + best);
  }
}
