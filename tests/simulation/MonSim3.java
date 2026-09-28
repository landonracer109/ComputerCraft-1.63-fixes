import java.util.*;
// Practical in-game setup: build a W x H wall, WorldEdit-copy only a sub-rectangle that contains the
// bottom-left (origin) block, paste it. Then random breaks/places. Find the simplest sequence that
// crashes the given variant: 0 = original, 1 = first patch (contract only), 2 = contract + merge.
public class MonSim3 {
  public static void main(String[] a) {
    int variant = Integer.parseInt(a[0]); int trials = Integer.parseInt(a[1]);
    Random r = new Random(11); int crashes = 0; String best = null; int bestScore = 1 << 30;
    for (int t = 0; t < trials; t++) {
      int W = 1 + r.nextInt(5), H = 1 + r.nextInt(4); int cw = 1 + r.nextInt(W), ch = 1 + r.nextInt(H);
      if (cw == W && ch == H) continue;
      List<int[]> ops = new ArrayList<int[]>(); int n = 1 + r.nextInt(5);
      for (int i = 0; i < n; i++) ops.add(new int[]{r.nextInt(3) == 0 ? 1 : 0, r.nextInt(W), r.nextInt(H)});
      for (int k = 1; k <= ops.size(); k++) {
        if (run(variant, W, H, cw, ch, ops.subList(0, k)) != null) {
          crashes++; int score = k * 100 + cw * ch * 10 + W * H;
          if (score < bestScore) { bestScore = score; StringBuilder b = new StringBuilder("wall " + W + "x" + H + ", copy the bottom-left " + cw + "x" + ch + ", paste; then");
            for (int[] o : ops.subList(0, k)) b.append(o[0] == 1 ? " place(" : " BREAK(").append(o[1]).append(',').append(o[2]).append(')'); best = b.toString(); }
          break;
        }
      }
    }
    System.out.println("variant " + variant + ": " + crashes + " crashing trials of " + trials + (best == null ? "" : "; simplest: " + best));
  }
  static String run(int variant, int W, int H, int cw, int ch, List<int[]> ops) {
    MonSim.PATCHED = variant > 0; MonSim.Mon.MERGEFIX = variant == 2; MonSim.Mon.loops = 0;
    MonSim.grid = new MonSim.Mon[MonSim.N][MonSim.N];
    for (int x = 0; x < cw; x++) for (int y = 0; y < ch; y++) { MonSim.Mon m = new MonSim.Mon(x, y); m.xi = x; m.yi = y; m.w = W; m.h = H; MonSim.grid[x][y] = m; }
    try { for (int[] op : ops) { if (op[0] == 1) { if (MonSim.grid[op[1]][op[2]] == null) MonSim.place(op[1], op[2]); } else if (MonSim.grid[op[1]][op[2]] != null) MonSim.brk(op[1], op[2]); } return null; }
    catch (NullPointerException e) { return "NPE"; } catch (IllegalStateException e) { return "LOOP"; }
  }
}
