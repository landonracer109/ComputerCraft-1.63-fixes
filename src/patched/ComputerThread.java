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

    private static void record(Task task, long waitNanos, long runNanos, boolean timedOut) {
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
            s_dropped.getAndSet(0), s_threadsCreated, REUSE_WORKER ? "reuse-worker" : "thread-per-task"));
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

    public static void start() {
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
        synchronized (m_lock) {
            if (m_running) {
                m_stopped = true;
                m_thread.interrupt();
            }
        }
    }

    public static void queueTask(Task _task, Computer computer) {
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
