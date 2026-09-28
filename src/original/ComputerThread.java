/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  dan200.computercraft.core.computer.Computer
 */
package dan200.computercraft.core.computer;

import dan200.computercraft.core.computer.Computer;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.WeakHashMap;
import java.util.concurrent.LinkedBlockingQueue;

public class ComputerThread {
    private static Object m_lock = new Object();
    private static Thread m_thread = null;
    private static WeakHashMap<Object, LinkedBlockingQueue<Task>> m_computerTasks = new WeakHashMap();
    private static ArrayList<LinkedBlockingQueue<Task>> m_computerTasksActive;
    private static ArrayList<LinkedBlockingQueue<Task>> m_computerTasksPending;
    private static Object m_defaultQueue;
    private static Object m_monitor;
    private static boolean m_busy;
    private static boolean m_running;
    private static boolean m_stopped;

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void start() {
        Object object = m_lock;
        synchronized (object) {
            if (m_running) {
                m_stopped = false;
                return;
            }
            m_thread = new Thread(new Runnable(){

                /*
                 * WARNING - Removed try catching itself - possible behaviour change.
                 */
                @Override
                public void run() {
                    block19: while (true) {
                        Object queue;
                        ArrayList arrayList = m_computerTasksPending;
                        synchronized (arrayList) {
                            if (!m_computerTasksPending.isEmpty()) {
                                Iterator it = m_computerTasksPending.iterator();
                                while (it.hasNext()) {
                                    queue = (LinkedBlockingQueue)it.next();
                                    if (!m_computerTasksActive.contains(queue)) {
                                        m_computerTasksActive.add(queue);
                                    }
                                    it.remove();
                                }
                            }
                        }
                        Iterator it = m_computerTasksActive.iterator();
                        while (it.hasNext()) {
                            LinkedBlockingQueue queue2;
                            block29: {
                                queue2 = (LinkedBlockingQueue)it.next();
                                if (queue2 == null || queue2.isEmpty()) continue;
                                queue = m_lock;
                                synchronized (queue) {
                                    if (m_stopped) {
                                        m_running = false;
                                        m_thread = null;
                                        return;
                                    }
                                }
                                try {
                                    final Task task = (Task)queue2.take();
                                    m_busy = true;
                                    Thread worker = new Thread(new Runnable(){

                                        @Override
                                        public void run() {
                                            try {
                                                task.execute();
                                            }
                                            catch (Throwable e) {
                                                System.out.println("computercraft: Error running task.");
                                                e.printStackTrace();
                                            }
                                        }
                                    });
                                    worker.start();
                                    worker.join(5000L);
                                    if (!worker.isAlive()) break block29;
                                    Computer computer = task.getOwner();
                                    if (computer != null) {
                                        computer.abort(false);
                                        worker.join(1250L);
                                        if (worker.isAlive()) {
                                            computer.abort(true);
                                            worker.join(1250L);
                                        }
                                    }
                                    if (!worker.isAlive()) break block29;
                                    worker.interrupt();
                                    worker.stop();
                                }
                                catch (InterruptedException e) {}
                                continue;
                                finally {
                                    m_busy = false;
                                    continue;
                                }
                            }
                            LinkedBlockingQueue e = queue2;
                            synchronized (e) {
                                if (queue2.isEmpty()) {
                                    it.remove();
                                }
                            }
                        }
                        while (true) {
                            if (!m_computerTasksActive.isEmpty() || !m_computerTasksPending.isEmpty()) continue block19;
                            Object object = m_monitor;
                            synchronized (object) {
                                try {
                                    m_monitor.wait();
                                }
                                catch (InterruptedException interruptedException) {
                                    // empty catch block
                                }
                            }
                        }
                        break;
                    }
                }
            });
            m_thread.start();
            m_running = true;
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void stop() {
        Object object = m_lock;
        synchronized (object) {
            if (m_running) {
                m_stopped = true;
                m_thread.interrupt();
            }
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public static void queueTask(Task _task, Computer computer) {
        LinkedBlockingQueue<Task> queue;
        Object queueObject = computer;
        if (queueObject == null) {
            queueObject = m_defaultQueue;
        }
        if ((queue = m_computerTasks.get(queueObject)) == null) {
            queue = new LinkedBlockingQueue(256);
            m_computerTasks.put(queueObject, queue);
        }
        Object object = m_computerTasksPending;
        synchronized (object) {
            queue.offer(_task);
            if (!m_computerTasksPending.contains(queue)) {
                m_computerTasksPending.add(queue);
            }
        }
        object = m_monitor;
        synchronized (object) {
            m_monitor.notify();
        }
    }

    static {
        m_computerTasksPending = new ArrayList();
        m_computerTasksActive = new ArrayList();
        m_defaultQueue = new Object();
        m_monitor = new Object();
        m_busy = false;
        m_running = false;
        m_stopped = false;
    }

    public static interface Task {
        public Computer getOwner();

        public void execute();
    }
}
