package dan200.computercraft.core.computer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * ComputerCraft 1.63's computer scheduler, rewritten from the decompiled original.
 *
 * Scheduling is unchanged by default: one task at a time, round-robin across computers' queues, each
 * task run on a new worker thread that gets 5 seconds before the computer is aborted (soft, then
 * hard, then the thread is stopped).
 *
 * Always on: a lost-wakeup fix. The original checked for work outside the lock it waits on, so a
 * task queued in between could sit until the next task arrived. Here the check and the wait happen
 * under the same lock.
 *
 * Optional, off unless set with Java arguments:
 *  -Dcc.profileSeconds=N   every N seconds print a "[CC-Profile]" report: how busy the computer
 *                          thread was, how long tasks waited, tasks dropped because a computer's
 *                          queue was full, and the computers that used the most time (all busy
 *                          ones, up to 40) with their longest task.
 *  -Dcc.reuseWorker=true   reuse one long-lived worker thread instead of creating a new thread for
 *                          every task. Only a worker that has to be stopped is replaced.
 *  -Dcc.threads=N          EXPERIMENTAL, N > 1: run computers on N long-lived worker threads (see
 *                          Pool below and dan200.computercraft.core.lua.WorldLock). Includes what
 *                          cc.reuseWorker does; cc.reuseWorker is ignored.
 */
public class ComputerThread {
    private static Object m_lock = new Object();
    private static Thread m_thread = null;
    private static WeakHashMap<Object, LinkedBlockingQueue<Task>> m_computerTasks = new WeakHashMap<Object, LinkedBlockingQueue<Task>>();
    private static ArrayList<LinkedBlockingQueue<Task>> m_computerTasksActive = new ArrayList<LinkedBlockingQueue<Task>>();
    private static ArrayList<LinkedBlockingQueue<Task>> m_computerTasksPending = new ArrayList<LinkedBlockingQueue<Task>>();
    private static Object m_defaultQueue = new Object();
    private static Object m_monitor = new Object();
    private static boolean m_busy = false;
    private static boolean m_running = false;
    private static boolean m_stopped = false;

    private static final boolean REUSE_WORKER = Boolean.getBoolean("cc.reuseWorker");
    private static final long REPORT_INTERVAL_NANOS = Long.getLong("cc.profileSeconds", 0L) * 1000L * 1000L * 1000L;
    private static final boolean PROFILE = REPORT_INTERVAL_NANOS > 0;

    public interface Task {
        Computer getOwner();
        void execute();
    }

    /** Wraps a queued task with the time it was queued. */
    private static final class TimedTask implements Task {
        final Task inner;
        final long queuedAt = System.nanoTime();
        TimedTask(Task inner) { this.inner = inner; }
        public Computer getOwner() { return inner.getOwner(); }
        public void execute() { inner.execute(); }
    }

    // ---- profiling (only touched by the dispatcher thread) ----

    private static final class ComputerStats {
        String name;
        int tasks;
        long runNanos;
        long maxRunNanos;
        int timeouts;
    }

    private static final Map<Integer, ComputerStats> s_stats = new HashMap<Integer, ComputerStats>();
    private static long s_periodStart = System.nanoTime();
    private static int s_tasks;
    private static long s_runNanos;
    private static long s_waitNanos;
    private static long s_maxWaitNanos;
    private static int s_threadsCreated;
    // queueTask runs on many threads; the original silently drops tasks when a computer has 256 queued
    private static final java.util.concurrent.atomic.AtomicInteger s_dropped = new java.util.concurrent.atomic.AtomicInteger();

    private static synchronized void record(Task task, long waitNanos, long runNanos, boolean timedOut) {
        if (!PROFILE) {
            return;
        }
        Computer owner = task.getOwner();
        int id = owner == null ? -1 : owner.getID();
        ComputerStats stats = s_stats.get(id);
        if (stats == null) {
            stats = new ComputerStats();
            s_stats.put(id, stats);
        }
        if (owner != null) {
            String label = owner.getLabel();
            stats.name = "#" + id + (label != null ? " \"" + label + "\"" : "");
        } else {
            stats.name = "(no computer)";
        }
        stats.tasks++;
        stats.runNanos += runNanos;
        stats.maxRunNanos = Math.max(stats.maxRunNanos, runNanos);
        if (timedOut) {
            stats.timeouts++;
        }
        s_tasks++;
        s_runNanos += runNanos;
        s_waitNanos += waitNanos;
        s_maxWaitNanos = Math.max(s_maxWaitNanos, waitNanos);
        maybeReport();
    }

    private static void maybeReport() {
        long now = System.nanoTime();
        long period = now - s_periodStart;
        if (period < REPORT_INTERVAL_NANOS) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("[CC-Profile] %.0fs: %d tasks, thread busy %.1f%%, queue wait avg %.1f ms / max %.0f ms, DROPPED (queue full) %d, worker threads created %d, mode %s%n",
            period / 1e9, s_tasks, 100.0 * s_runNanos / period,
            s_tasks == 0 ? 0.0 : s_waitNanos / 1e6 / s_tasks, s_maxWaitNanos / 1e6,
            s_dropped.getAndSet(0), s_threadsCreated, THREADS > 1 ? THREADS + " threads (busy % is summed over them)" : REUSE_WORKER ? "reuse-worker" : "thread-per-task"));
        ArrayList<ComputerStats> list = new ArrayList<ComputerStats>(s_stats.values());
        java.util.Collections.sort(list, new java.util.Comparator<ComputerStats>() {
            public int compare(ComputerStats a, ComputerStats b) {
                return a.runNanos < b.runNanos ? 1 : (a.runNanos > b.runNanos ? -1 : 0);
            }
        });
        int shown = 0;
        for (ComputerStats s : list) {
            // every computer that used at least 0.5% of the period, at least 15, at most 40
            if (shown >= 40 || (shown >= 15 && s.runNanos * 200 < period)) {
                break;
            }
            sb.append(String.format("[CC-Profile]   %-28s %6.1f%% of period, %5d tasks, avg %6.2f ms, max %7.1f ms%s%n",
                s.name, 100.0 * s.runNanos / period, s.tasks, s.runNanos / 1e6 / s.tasks, s.maxRunNanos / 1e6,
                s.timeouts > 0 ? ", " + s.timeouts + " timed out" : ""));
            shown++;
        }
        System.out.print(sb.toString());
        s_stats.clear();
        s_periodStart = now;
        s_tasks = 0;
        s_runNanos = 0;
        s_waitNanos = 0;
        s_maxWaitNanos = 0;
        s_threadsCreated = 0;
    }

    // ---- workers ----

    /** A reusable worker: runs one task at a time handed to it by the dispatcher. */
    private static final class Worker implements Runnable {
        final Thread thread;
        private Task m_task;
        private boolean m_done;

        Worker() {
            thread = new Thread(this, "Computer Worker");
            thread.setDaemon(true);
            s_threadsCreated++;
            thread.start();
        }

        synchronized void submit(Task task) {
            m_task = task;
            m_done = false;
            notifyAll();
        }

        /** Waits up to the given time for the current task; returns true if it finished. */
        synchronized boolean await(long millis) throws InterruptedException {
            long end = System.currentTimeMillis() + millis;
            while (!m_done) {
                long left = end - System.currentTimeMillis();
                if (left <= 0) {
                    return false;
                }
                wait(left);
            }
            return true;
        }

        public void run() {
            while (true) {
                Task task;
                synchronized (this) {
                    while (m_task == null) {
                        try {
                            wait();
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                    task = m_task;
                }
                try {
                    task.execute();
                } catch (Throwable e) {
                    System.out.println("computercraft: Error running task.");
                    e.printStackTrace();
                }
                synchronized (this) {
                    m_task = null;
                    m_done = true;
                    notifyAll();
                }
            }
        }
    }

    private static Worker s_worker;

    /** Runs one task the original way: a new thread per task. Returns true if it timed out. */
    private static boolean runOnNewThread(final Task task) throws InterruptedException {
        Thread worker = new Thread(new Runnable() {
            public void run() {
                try {
                    task.execute();
                } catch (Throwable e) {
                    System.out.println("computercraft: Error running task.");
                    e.printStackTrace();
                }
            }
        });
        s_threadsCreated++;
        worker.start();
        worker.join(5000L);
        if (!worker.isAlive()) {
            return false;
        }
        abortStuck(task, worker);
        return true;
    }

    /** Runs one task on the reusable worker. Returns true if it timed out. */
    private static boolean runOnReusedWorker(Task task) throws InterruptedException {
        if (s_worker == null || !s_worker.thread.isAlive()) {
            s_worker = new Worker();
        }
        Worker worker = s_worker;
        worker.submit(task);
        if (worker.await(5000L)) {
            return false;
        }
        Computer computer = task.getOwner();
        if (computer != null) {
            computer.abort(false);
            if (worker.await(1250L)) {
                return true;
            }
            computer.abort(true);
            if (worker.await(1250L)) {
                return true;
            }
        }
        // Stuck for good: kill it like the original does, and start a fresh worker next time.
        worker.thread.interrupt();
        worker.thread.stop();
        s_worker = null;
        return true;
    }

    /** The original's escalation for a task that didn't finish within 5 seconds. */
    @SuppressWarnings("deprecation")
    private static void abortStuck(Task task, Thread worker) throws InterruptedException {
        Computer computer = task.getOwner();
        if (computer != null) {
            computer.abort(false);
            worker.join(1250L);
            if (worker.isAlive()) {
                computer.abort(true);
                worker.join(1250L);
            }
        }
        if (worker.isAlive()) {
            worker.interrupt();
            worker.stop();
        }
    }

    // ---- parallel mode (-Dcc.threads=N, N > 1) ----

    private static final int THREADS = Math.max(1, Integer.getInteger("cc.threads", 1));

    /**
     * Runs computers on N worker threads. Each computer's tasks still run one at a time and in
     * order; different computers can run at once. Only computers' pure Lua runs in parallel:
     * dan200.computercraft.core.lua.WorldLock keeps everything else (every task's Java code and every
     * API call: world, files, peripherals, rednet) one computer at a time, as in the original. A
     * watchdog gives each task the original's 5 s + 1.25 s + 1.25 s before aborting, not counting
     * time spent waiting for the world lock.
     */
    private static final class Pool {
        static final Object LOCK = new Object();
        static final java.util.LinkedList<LinkedBlockingQueue<Task>> READY = new java.util.LinkedList<LinkedBlockingQueue<Task>>();
        static final java.util.Set<LinkedBlockingQueue<Task>> SCHEDULED = new java.util.HashSet<LinkedBlockingQueue<Task>>();
        static final java.util.List<PoolWorker> WORKERS = new ArrayList<PoolWorker>();
        static boolean s_running, s_stopped;
        static Thread s_watchdog;


        static void start() {
            synchronized (LOCK) {
                s_stopped = false;
                if (s_running) {
                    return;
                }
                s_running = true;
                for (int i = 0; i < THREADS; i++) {
                    WORKERS.add(new PoolWorker());
                }
                s_watchdog = new Thread(new Runnable() {
                    public void run() {
                        watchdog();
                    }
                }, "Computer Watchdog");
                s_watchdog.setDaemon(true);
                s_watchdog.start();
            }
        }

        static void stop() {
            synchronized (LOCK) {
                if (!s_running) {
                    return;
                }
                s_stopped = true;
                s_running = false;
                LOCK.notifyAll();
                WORKERS.clear();
                s_watchdog.interrupt();
            }
        }

        static void queue(Task task, Computer computer) {
            Object key = computer == null ? m_defaultQueue : computer;
            synchronized (LOCK) {
                LinkedBlockingQueue<Task> queue = m_computerTasks.get(key);
                if (queue == null) {
                    queue = new LinkedBlockingQueue<Task>(256);
                    m_computerTasks.put(key, queue);
                }
                if (!queue.offer(PROFILE ? new TimedTask(task) : task)) {
                    s_dropped.incrementAndGet();
                }
                if (SCHEDULED.add(queue)) {
                    READY.addLast(queue);
                    LOCK.notify();
                }
            }
        }

        static void watchdog() {
            while (true) {
                try {
                    Thread.sleep(50L);
                } catch (InterruptedException e) {
                    // re-check below
                }
                ArrayList<PoolWorker> workers;
                synchronized (LOCK) {
                    if (s_stopped) {
                        return;
                    }
                    workers = new ArrayList<PoolWorker>(WORKERS);
                }
                for (PoolWorker w : workers) {
                    w.check();
                }
            }
        }
    }

    private static final class PoolWorker implements Runnable {
        final Thread thread;
        // the task being run, guarded by this
        private Task m_task;
        private long m_started;
        private int m_stage;
        private boolean m_dead;

        PoolWorker() {
            thread = new Thread(this, "Computer Worker");
            thread.setDaemon(true);
            synchronized (ComputerThread.class) {
                s_threadsCreated++;
            }
            thread.start();
        }

        private boolean isDead() {
            synchronized (this) {
                return m_dead;
            }
        }

        public void run() {
            while (true) {
                LinkedBlockingQueue<Task> queue;
                Task task;
                synchronized (Pool.LOCK) {
                    while (Pool.READY.isEmpty() || isDead()) {
                        if (Pool.s_stopped || isDead()) {
                            return;
                        }
                        try {
                            Pool.LOCK.wait();
                        } catch (InterruptedException e) {
                            // re-check
                        }
                    }
                    if (Pool.s_stopped) {
                        return;
                    }
                    queue = Pool.READY.removeFirst();
                    task = queue.poll();
                }
                Computer owner = task == null ? null : task.getOwner();
                Object token = owner != null ? owner : m_defaultQueue;
                long started = System.nanoTime();
                boolean timedOut = false;
                try {
                    if (task != null) {
                        synchronized (this) {
                            m_task = task;
                            m_started = started;
                            m_stage = 0;
                        }
                        try {
                            dan200.computercraft.core.lua.WorldLock.beginTask(token);
                            task.execute();
                        } catch (ThreadDeath d) {
                            throw d;
                        } catch (Throwable e) {
                            System.out.println("computercraft: Error running task.");
                            e.printStackTrace();
                        }
                    }
                } finally {
                    synchronized (this) {
                        timedOut = m_stage > 0;
                        m_task = null;
                    }
                    dan200.computercraft.core.lua.WorldLock.endTask(token);
                    synchronized (Pool.LOCK) {
                        if (queue.isEmpty()) {
                            Pool.SCHEDULED.remove(queue);
                        } else {
                            Pool.READY.addLast(queue);
                            Pool.LOCK.notify();
                        }
                    }
                }
                if (task != null) {
                    long waited = task instanceof TimedTask ? started - ((TimedTask) task).queuedAt : 0L;
                    record(task, waited, System.nanoTime() - started, timedOut);
                }
            }
        }

        /** Called by the watchdog: the original's 5 s soft abort, +1.25 s hard abort, +1.25 s stop. */
        @SuppressWarnings("deprecation")
        void check() {
            Task task;
            long started;
            int stage;
            synchronized (this) {
                task = m_task;
                if (task == null || m_dead) {
                    return;
                }
                started = m_started;
                stage = m_stage;
            }
            Computer owner = task.getOwner();
            long ms = (System.nanoTime() - started - dan200.computercraft.core.lua.WorldLock.waitedNanos(owner != null ? owner : m_defaultQueue)) / 1000000L;
            if (stage == 0 && ms >= 5000L) {
                if (setStage(task, 1) && owner != null) {
                    owner.abort(false);
                }
            } else if (stage == 1 && ms >= 6250L) {
                if (setStage(task, 2) && owner != null) {
                    owner.abort(true);
                }
            } else if (stage == 2 && ms >= 7500L) {
                if (!setStage(task, 3)) {
                    return;
                }
                // Stuck for good: stop it like the original, free the world lock it may hold, and
                // replace the worker so the other computers keep running.
                synchronized (this) {
                    m_dead = true;
                }
                synchronized (Pool.LOCK) {
                    Pool.WORKERS.remove(this);
                    if (!Pool.s_stopped) {
                        Pool.WORKERS.add(new PoolWorker());
                    }
                }
                dan200.computercraft.core.lua.WorldLock.forceRelease(owner != null ? owner : m_defaultQueue);
                thread.interrupt();
                thread.stop();
            }
        }

        private synchronized boolean setStage(Task task, int stage) {
            if (m_task != task) {
                return false;
            }
            m_stage = stage;
            return true;
        }
    }

    public static void start() {
        if (THREADS > 1) {
            Pool.start();
            return;
        }
        synchronized (m_lock) {
            if (m_running) {
                m_stopped = false;
                return;
            }
            m_thread = new Thread(new Runnable() {
                public void run() {
                    while (true) {
                        synchronized (m_computerTasksPending) {
                            if (!m_computerTasksPending.isEmpty()) {
                                Iterator<LinkedBlockingQueue<Task>> it = m_computerTasksPending.iterator();
                                while (it.hasNext()) {
                                    LinkedBlockingQueue<Task> queue = it.next();
                                    if (!m_computerTasksActive.contains(queue)) {
                                        m_computerTasksActive.add(queue);
                                    }
                                    it.remove();
                                }
                            }
                        }
                        Iterator<LinkedBlockingQueue<Task>> it = m_computerTasksActive.iterator();
                        while (it.hasNext()) {
                            LinkedBlockingQueue<Task> queue = it.next();
                            if (queue == null || queue.isEmpty()) {
                                continue;
                            }
                            synchronized (m_lock) {
                                if (m_stopped) {
                                    m_running = false;
                                    m_thread = null;
                                    return;
                                }
                            }
                            try {
                                Task task = queue.take();
                                m_busy = true;
                                long started = System.nanoTime();
                                long waited = task instanceof TimedTask ? started - ((TimedTask) task).queuedAt : 0L;
                                boolean timedOut = REUSE_WORKER ? runOnReusedWorker(task) : runOnNewThread(task);
                                record(task, waited, System.nanoTime() - started, timedOut);
                            } catch (InterruptedException e) {
                                // original: carry on with the next queue
                            } finally {
                                m_busy = false;
                            }
                            synchronized (queue) {
                                if (queue.isEmpty()) {
                                    it.remove();
                                }
                            }
                        }
                        // Check and wait under the monitor that queueTask notifies, so a task queued
                        // between the check and the wait can't be missed (the original checked
                        // outside the lock and could sleep until the next task arrived).
                        synchronized (m_monitor) {
                            while (m_computerTasksActive.isEmpty() && m_computerTasksPending.isEmpty()) {
                                synchronized (m_lock) {
                                    if (m_stopped) {
                                        m_running = false;
                                        m_thread = null;
                                        return;
                                    }
                                }
                                try {
                                    m_monitor.wait();
                                } catch (InterruptedException e) {
                                    // re-check (stop() interrupts to wake us)
                                }
                            }
                        }
                    }
                }
            });
            m_thread.start();
            m_running = true;
        }
    }

    public static void stop() {
        if (THREADS > 1) {
            Pool.stop();
            return;
        }
        synchronized (m_lock) {
            if (m_running) {
                m_stopped = true;
                m_thread.interrupt();
            }
        }
    }

    public static void queueTask(Task _task, Computer computer) {
        if (THREADS > 1) {
            Pool.queue(_task, computer);
            return;
        }
        Object queueObject = computer;
        if (queueObject == null) {
            queueObject = m_defaultQueue;
        }
        LinkedBlockingQueue<Task> queue = m_computerTasks.get(queueObject);
        if (queue == null) {
            queue = new LinkedBlockingQueue<Task>(256);
            m_computerTasks.put(queueObject, queue);
        }
        synchronized (m_computerTasksPending) {
            if (!queue.offer(PROFILE ? new TimedTask(_task) : _task)) {
                s_dropped.incrementAndGet(); // same drop as the original, now counted
            }
            if (!m_computerTasksPending.contains(queue)) {
                m_computerTasksPending.add(queue);
            }
        }
        synchronized (m_monitor) {
            m_monitor.notify();
        }
    }
}
