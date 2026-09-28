import java.util.*;
// Simulation of ComputerCraft 1.63 monitor wall logic (TileMonitor: expand/merge*/resize/contract/
// contractNeighbours/destroy), transcribed from the decompiled class. A wall is a 2D grid: index x
// grows to the right, index y grows upward (getDown() is +Y for wall monitors). Placing a block
// makes a 1x1 monitor and calls expand(); breaking one calls destroy() then removes the tile.
public class MonSim {
  static boolean PATCHED;
  static final int N = 9;
  static Mon[][] grid;
  static final class Mon {
    int wx, wy, xi, yi, w = 1, h = 1; boolean destroyed, ignore;
    Mon(int x, int y) { wx = x; wy = y; }
    Mon at(int x, int y) { if (x < 0 || y < 0 || x >= N || y >= N) return null; Mon m = grid[x][y]; return m != null && !m.destroyed && !m.ignore ? m : null; }
    Mon nb(int x, int y) { return at(wx - xi + x, wy - yi + y); }
    Mon origin() { return nb(0, 0); }
    void resize(int width, int height) {
      for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) { Mon m = at(wx + x, wy + y); if (m == null) continue; m.xi = x; m.yi = y; m.w = width; m.h = height; }
    }
    static int loops; static boolean MERGEFIX = Boolean.getBoolean("mergefix");
    static boolean mergeInto(Mon o, int a, int b, Mon ex) { if (++loops > 100000) throw new IllegalStateException("loop"); if (MERGEFIX && PATCHED && o == null) return false; o.resize(a, b); ex.expand(); return true; }
    boolean mergeLeft() { Mon l = nb(-1, 0); int width; if (l != null && l.yi == 0 && l.h == h && (width = l.w + w) <= 8) { return mergeInto(l.origin(), width, h, l); } return false; }
    boolean mergeRight() { Mon r = nb(w, 0); int width; if (r != null && r.yi == 0 && r.h == h && (width = w + r.w) <= 8) { return mergeInto(origin(), width, h, this); } return false; }
    boolean mergeUp() { Mon a = nb(0, h); int height; if (a != null && a.xi == 0 && a.w == w && (height = a.h + h) <= 6) { return mergeInto(origin(), w, height, this); } return false; }
    boolean mergeDown() { Mon b = nb(0, -1); int height; if (b != null && b.xi == 0 && b.w == w && (height = h + b.h) <= 6) { return mergeInto(b.origin(), w, height, b); } return false; }
    void expand() { while (mergeLeft() || mergeRight() || mergeUp() || mergeDown()) { } }
    void contractNeighbours() {
      ignore = true; Mon m;
      if (xi > 0 && (m = nb(xi - 1, yi)) != null) m.contract();
      if (xi + 1 < w && (m = nb(xi + 1, yi)) != null) m.contract();
      if (yi > 0 && (m = nb(xi, yi - 1)) != null) m.contract();
      if (yi + 1 < h && (m = nb(xi, yi + 1)) != null) m.contract();
      ignore = false;
    }
    static void rs(Mon m, int a, int b) { if (PATCHED && m == null) return; m.resize(a, b); } // original: NPE when null
    void contract() {
      int height = h, width = w; Mon origin = origin();
      if (origin == null) {
        Mon right = null, below = null;
        if (width > 1) right = nb(1, 0);
        if (height > 1) below = nb(0, 1);
        if (right != null) rs(right, width - 1, 1);
        if (below != null) rs(below, width, height - 1);
        if (right != null) right.expand();
        if (below != null) below.expand();
        return;
      }
      for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
        if (origin.nb(x, y) != null) continue;
        Mon above = null, left = null, right = null, below = null;
        if (y > 0) { above = origin; rs(above, width, y); }
        if (x > 0) { left = origin.nb(0, y); rs(left, x, 1); }
        if (x + 1 < width) { right = origin.nb(x + 1, y); rs(right, width - (x + 1), 1); }
        if (y + 1 < height) { below = origin.nb(0, y + 1); rs(below, width, height - (y + 1)); }
        if (above != null) above.expand();
        if (left != null) left.expand();
        if (right != null) right.expand();
        if (below != null) below.expand();
        return;
      }
    }
  }
  static void place(int x, int y) { Mon m = new Mon(x, y); grid[x][y] = m; m.expand(); }
  static void brk(int x, int y) { Mon m = grid[x][y]; if (!m.destroyed) { m.destroyed = true; m.contractNeighbours(); } grid[x][y] = null; }
  // A tile is consistent if its wall's origin exists, agrees on the size, and every tile of the wall has the right indices.
  static int inconsistent() {
    int bad = 0;
    for (int x = 0; x < N; x++) for (int y = 0; y < N; y++) { Mon m = grid[x][y]; if (m == null) continue;
      Mon o = m.origin(); boolean ok = o != null && o.w == m.w && o.h == m.h;
      if (ok) for (int j = 0; j < m.h && ok; j++) for (int i = 0; i < m.w && ok; i++) { Mon t = o.nb(i, j); ok = t != null && t.xi == i && t.yi == j && t.w == m.w && t.h == m.h; }
      if (!ok) bad++; }
    return bad;
  }
  static String run(List<int[]> ops) { // replay; returns null or the error
    grid = new Mon[N][N];
    try { for (int[] op : ops) { if (op[0] == 1) place(op[1], op[2]); else brk(op[1], op[2]); } return null; }
    catch (NullPointerException e) { return "NPE"; }
  }
  static String show(List<int[]> ops) { StringBuilder b = new StringBuilder(); for (int[] o : ops) b.append(o[0] == 1 ? " place(" : " BREAK(").append(o[1]).append(',').append(o[2]).append(')'); return b.toString(); }
  public static void main(String[] a) {
    Random r = new Random(1);
    int trials = Integer.parseInt(a[0]); int origCrash = 0, patchedCrash = 0, patchedBadRuns = 0, origBadRuns = 0;
    List<int[]> shortest = null;
    for (int t = 0; t < trials; t++) {
      int W = 2 + r.nextInt(4), H = 2 + r.nextInt(4); // wall size to build, 2..5
      List<int[]> ops = new ArrayList<int[]>();
      List<int[]> cells = new ArrayList<int[]>();
      for (int x = 0; x < W; x++) for (int y = 0; y < H; y++) cells.add(new int[]{x, y});
      Collections.shuffle(cells, r);
      for (int[] c : cells) ops.add(new int[]{1, c[0], c[1]});
      Collections.shuffle(cells, r);
      int breaks = 1 + r.nextInt(cells.size());
      for (int i = 0; i < breaks; i++) ops.add(new int[]{0, cells.get(i)[0], cells.get(i)[1]});
      PATCHED = false; String e1 = run(ops); if (e1 == null && inconsistent() > 0) origBadRuns++;
      if (e1 != null) { origCrash++;
        // shrink: find the shortest prefix that crashes
        for (int k = 1; k <= ops.size(); k++) { List<int[]> p = ops.subList(0, k); if (run(p) != null) { if (shortest == null || p.size() < shortest.size() || (p.size() == shortest.size() && W * H < 0)) shortest = new ArrayList<int[]>(p); break; } } }
      PATCHED = true; String e2 = run(ops); if (e2 != null) patchedCrash++; else if (inconsistent() > 0) patchedBadRuns++;
    }
    System.out.println("trials " + trials + ": original crashed " + origCrash + ", patched crashed " + patchedCrash
      + "; runs ending with an inconsistent wall: original(non-crash) " + origBadRuns + ", patched " + patchedBadRuns);
    if (shortest != null) System.out.println("shortest crashing sequence (" + shortest.size() + " ops):" + show(shortest));
  }
}
