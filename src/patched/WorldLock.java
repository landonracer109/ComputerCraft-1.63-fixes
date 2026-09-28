package dan200.computercraft.core.lua;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Keeps parallel computers (-Dcc.threads=N, N > 1) behaving as if they ran one at a time.
 *
 * A computer holds this lock for its whole scheduler task (starting up, handling an event,
 * shutting down, peripherals attaching), exactly like the original single computer thread. The
 * only thing that runs without it is the computer's own Lua code between API calls, which can't
 * affect anything outside that computer:
 *
 *   task starts .............. take the lock
 *   Lua resumes .............. let it go            (pure Lua runs in parallel with others)
 *   Lua calls a Java API ..... take it, and keep it (term, fs, peripheral, rednet, turtle, ...)
 *   Lua hands back control ... take it (if not held)
 *   task ends ................ let it go
 *
 * So once a computer has touched anything in an event, no other computer can touch anything until
 * that event is over, as before. The lock is owned by the computer, not a Java thread: API calls
 * run on the computer's coroutine threads, the rest on a scheduler worker.
 *
 * Computers get the lock in the order they asked for it (a ticket queue), so none can be starved.
 * Time spent waiting for it is recorded so the "Too long without yielding" watchdog leaves it out.
 */
public final class WorldLock {
    public static final boolean ENABLED = Integer.getInteger("cc.threads", 1) > 1;

    private static final Object LOCK = new Object();
    private static Object s_owner;
    private static long s_nextTicket;
    private static long s_serving;
    /** Per computer, during its current task: [0] = nanoseconds waited so far, [1] = waiting since (0 = not waiting). */
    private static final Map<Object, long[]> s_waited = new HashMap<Object, long[]>();
    /** Which computer each Lua machine belongs to (learned when the machine is first resumed). */
    private static final Map<Object, Object> s_computerOf = new WeakHashMap<Object, Object>();
    /** The computer whose task this scheduler worker is running. */
    private static final ThreadLocal<Object> s_task = new ThreadLocal<Object>();

    private WorldLock() {
    }

    // ---- called by the scheduler worker ----

    public static void beginTask(Object computer) {
        if (!ENABLED) {
            return;
        }
        s_task.set(computer);
        acquire(computer);
    }

    public static void endTask(Object computer) {
        if (!ENABLED) {
            return;
        }
        s_task.remove();
        synchronized (LOCK) {
            s_waited.remove(computer);
            if (s_owner == computer) {
                s_owner = null;
                LOCK.notifyAll();
            }
        }
    }

    // ---- called from LuaJLuaMachine ----

    /** handleEvent, just before resuming the computer's Lua: pure Lua runs without the lock. */
    public static void beforeResume(Object machine) {
        if (!ENABLED) {
            return;
        }
        Object computer = s_task.get();
        if (computer == null) {
            return;
        }
        synchronized (LOCK) {
            s_computerOf.put(machine, computer);
            if (s_owner == computer) {
                s_owner = null;
                LOCK.notifyAll();
            }
        }
    }

    /** handleEvent, when the computer's Lua has handed control back. */
    public static void afterResume(Object machine) {
        if (!ENABLED) {
            return;
        }
        Object computer = s_task.get();
        if (computer != null) {
            acquire(computer);
        }
    }

    /** Before every Java API call from Lua, and when an API call resumes after waiting for an event. */
    public static void apiCall(Object machine) {
        if (!ENABLED) {
            return;
        }
        Object computer;
        synchronized (LOCK) {
            computer = s_computerOf.get(machine);
        }
        acquire(computer != null ? computer : machine);
    }

    // ---- the lock itself ----

    private static void acquire(Object who) {
        synchronized (LOCK) {
            if (s_owner == who) {
                return;
            }
            long ticket = s_nextTicket++;
            if (s_owner != null || s_serving != ticket) {
                long[] w = s_waited.get(who);
                if (w == null) {
                    w = new long[2];
                    s_waited.put(who, w);
                }
                w[1] = System.nanoTime();
                boolean interrupted = false;
                try {
                    while (s_owner != null || s_serving != ticket) {
                        try {
                            LOCK.wait();
                        } catch (InterruptedException e) {
                            interrupted = true;
                        }
                    }
                } finally {
                    w[0] += System.nanoTime() - w[1];
                    w[1] = 0L;
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
            s_serving++;
            s_owner = who;
        }
    }

    /** The watchdog gave up on a stuck task: free the lock if that computer holds it. */
    public static void forceRelease(Object computer) {
        if (!ENABLED) {
            return;
        }
        synchronized (LOCK) {
            if (s_owner == computer) {
                s_owner = null;
                LOCK.notifyAll();
            }
        }
    }

    /** Nanoseconds the computer has spent waiting for the lock during its current task, including now. */
    public static long waitedNanos(Object computer) {
        if (!ENABLED) {
            return 0L;
        }
        synchronized (LOCK) {
            long[] w = s_waited.get(computer);
            if (w == null) {
                return 0L;
            }
            return w[0] + (w[1] != 0L ? System.nanoTime() - w[1] : 0L);
        }
    }
}
