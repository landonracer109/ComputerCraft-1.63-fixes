import java.lang.reflect.Field;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.computer.ComputerThread;

/**
 * Simulates a server's computer load over time against the (patched) ComputerThread.
 * args: dashboards dashCostMs dashRatePerSec turtles turtleCostMs turtleRatePerSec seconds
 * Each computer gets events at random times at its rate (Poisson); each event costs its CPU time.
 * Measures thread utilisation, time from event to the computer handling it, and dropped events.
 */
public class LoadTest {
    static volatile long sink;
    static final AtomicInteger ran = new AtomicInteger();
    static final AtomicLong latencySum = new AtomicLong();
    static final AtomicLong latencyMax = new AtomicLong();
    static final AtomicLong dashLatencySum = new AtomicLong();
    static final AtomicInteger dashRan = new AtomicInteger();

    static Computer fakeComputer(int id, String label) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        Computer c = (Computer) unsafe.allocateInstance(Computer.class);
        Field idField = Computer.class.getDeclaredField("m_id");
        idField.setAccessible(true);
        idField.setInt(c, id);
        Field l = Computer.class.getDeclaredField("m_label");
        l.setAccessible(true);
        l.set(c, label);
        return c;
    }

    static void busy(long nanos) {
        long t = System.nanoTime(), x = 0;
        while (System.nanoTime() - t < nanos) x++;
        sink += x;
    }

    public static void main(String[] a) throws Exception {
        final int dashboards = Integer.parseInt(a[0]);
        final double dashCost = Double.parseDouble(a[1]), dashRate = Double.parseDouble(a[2]);
        final int turtles = Integer.parseInt(a[3]);
        final double turtleCost = Double.parseDouble(a[4]), turtleRate = Double.parseDouble(a[5]);
        final double seconds = Double.parseDouble(a[6]);
        int n = dashboards + turtles;
        final Computer[] owners = new Computer[n];
        final double[] rate = new double[n], cost = new double[n];
        for (int i = 0; i < n; i++) {
            boolean dash = i < dashboards;
            owners[i] = fakeComputer(i, dash ? "Dashboard " + i : "Turtle " + i);
            rate[i] = dash ? dashRate : turtleRate;
            cost[i] = dash ? dashCost : turtleCost;
        }
        ComputerThread.start();
        Random rnd = new Random(42);
        double[] next = new double[n];
        for (int i = 0; i < n; i++) next[i] = -Math.log(1 - rnd.nextDouble()) / rate[i];
        long start = System.nanoTime();
        int offered = 0;
        while (true) {
            double now = (System.nanoTime() - start) / 1e9;
            if (now >= seconds) break;
            for (int i = 0; i < n; i++) {
                while (next[i] <= now) {
                    final int idx = i;
                    final long queuedAt = System.nanoTime();
                    final long c = (long) (cost[i] * 1e6);
                    final Computer owner = owners[i];
                    final boolean dash = i < dashboards;
                    ComputerThread.queueTask(new ComputerThread.Task() {
                        public Computer getOwner() { return owner; }
                        public void execute() {
                            long lat = System.nanoTime() - queuedAt;
                            busy(c);
                            ran.incrementAndGet();
                            latencySum.addAndGet(lat);
                            if (dash) { dashRan.incrementAndGet(); dashLatencySum.addAndGet(lat); }
                            long m;
                            while (lat > (m = latencyMax.get()) && !latencyMax.compareAndSet(m, lat)) { }
                        }
                    }, owner);
                    offered++;
                    next[i] += -Math.log(1 - rnd.nextDouble()) / rate[i];
                }
            }
            Thread.sleep(1);
        }
        Thread.sleep(3000); // drain
        double offeredLoad = (dashboards * dashRate * dashCost + turtles * turtleRate * turtleCost) / 1000.0;
        System.out.println(String.format(
            "RESULT dashboards=%d mode=%s demand=%.0f%% of one core | ran %d/%d (dropped %d) | avg wait %.0f ms (dashboards %.0f ms), worst %.0f ms",
            dashboards, Boolean.getBoolean("cc.reuseWorker") ? "reuse " : "thread", offeredLoad * 100,
            ran.get(), offered, offered - ran.get(),
            ran.get() == 0 ? 0 : latencySum.get() / 1e6 / ran.get(),
            dashRan.get() == 0 ? 0 : dashLatencySum.get() / 1e6 / dashRan.get(),
            latencyMax.get() / 1e6));
        System.exit(0);
    }
}
