import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import dan200.computercraft.core.computer.Computer;
import dan200.computercraft.core.computer.ComputerThread;

/**
 * Drives the (patched) ComputerThread with fake computers.
 * args: paced | flood
 *  paced: keeps at most 100 tasks waiting per computer (nothing dropped); measures throughput.
 *  flood: queues everything in bursts; counts tasks that never ran (dropped by the 256 cap).
 */
public class SchedTest {
    static volatile long sink;
    static final int COMPUTERS = 20, TASKS_EACH = 500;
    static final Computer[] owners = new Computer[COMPUTERS];
    static final AtomicInteger[] doneBy = new AtomicInteger[COMPUTERS];
    static final int[] lastSeen = new int[COMPUTERS];
    static final AtomicInteger done = new AtomicInteger();
    static final AtomicInteger outOfOrder = new AtomicInteger();

    static Computer fakeComputer(int id) throws Exception {
        Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
        Computer c = (Computer) unsafe.allocateInstance(Computer.class);
        Field idField = Computer.class.getDeclaredField("m_id");
        idField.setAccessible(true);
        idField.setInt(c, id);
        Field label = Computer.class.getDeclaredField("m_label");
        label.setAccessible(true);
        label.set(c, id == 0 ? "ControlCPU" : "Turtle " + id);
        return c;
    }

    static void queue(final int comp, final int seq) {
        final Computer owner = owners[comp];
        ComputerThread.queueTask(new ComputerThread.Task() {
            public Computer getOwner() { return owner; }
            public void execute() {
                long t = System.nanoTime();
                long x = 0;
                long work = comp == 0 ? 1000000L : 200000L; // computer 0 is a busy dashboard
                while (System.nanoTime() - t < work) { x++; }
                sink += x;
                if (lastSeen[comp] >= seq) outOfOrder.incrementAndGet();
                lastSeen[comp] = seq;
                doneBy[comp].incrementAndGet();
                done.incrementAndGet();
            }
        }, owner);
    }

    public static void main(String[] args) throws Exception {
        boolean paced = args[0].equals("paced");
        for (int c = 0; c < COMPUTERS; c++) {
            owners[c] = fakeComputer(c);
            doneBy[c] = new AtomicInteger();
        }
        ComputerThread.start();
        long start = System.nanoTime();
        int total = COMPUTERS * TASKS_EACH;
        if (paced) {
            int[] queued = new int[COMPUTERS];
            while (done.get() < total) {
                for (int c = 0; c < COMPUTERS; c++) {
                    while (queued[c] < TASKS_EACH && queued[c] - doneBy[c].get() < 100) {
                        queue(c, ++queued[c]);
                    }
                }
                Thread.sleep(1);
                if ((System.nanoTime() - start) / 1e9 > 120) {
                    System.out.println("RESULT HUNG in paced mode at " + done.get() + "/" + total);
                    System.exit(1);
                }
            }
        } else {
            for (int n = 0; n < TASKS_EACH; n++) {
                for (int c = 0; c < COMPUTERS; c++) queue(c, n + 1);
                if (n % 100 == 99) Thread.sleep(50);
            }
            int last = -1;
            long idleSince = System.nanoTime();
            while (done.get() < total) {
                Thread.sleep(50);
                int d = done.get();
                if (d != last) { last = d; idleSince = System.nanoTime(); }
                else if ((System.nanoTime() - idleSince) / 1e9 > 3) break; // nothing left to run
            }
        }
        long ms = (System.nanoTime() - start) / 1000000;
        Thread.sleep(2500); // let the profiler print its last report
        System.out.println("RESULT " + args[0] + " mode=" + (Boolean.getBoolean("cc.reuseWorker") ? "reuse-worker" : "thread-per-task")
            + " ran=" + done.get() + "/" + total + " neverRan=" + (total - done.get())
            + " outOfOrder=" + outOfOrder.get() + " wallMs=" + ms
            + (done.get() > 0 ? String.format(" throughput=%.0f tasks/s", done.get() * 1000.0 / ms) : ""));
        System.exit(0);
    }
}
