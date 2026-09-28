package org.luaj.vm2;

/**
 * Per-Java-thread replacements for LuaThread's "running" and "main" coroutine, which LuaJ 2.0.3
 * keeps in static fields. Every coroutine already runs on its own Java thread, so tracking them
 * per thread gives the same answers with one computer running and keeps computers apart when
 * several run at once.
 */
public final class LuaThreadLocals {
    private static final ThreadLocal<LuaThread> MAIN = new ThreadLocal<LuaThread>();
    private static final ThreadLocal<LuaThread> RUNNING = new ThreadLocal<LuaThread>();

    private LuaThreadLocals() {
    }

    public static LuaThread main() {
        LuaThread t = MAIN.get();
        if (t == null) {
            t = LuaThread.newMainThread();
            MAIN.set(t);
        }
        return t;
    }

    public static void setMain(LuaThread t) {
        MAIN.set(t);
    }

    public static LuaThread running() {
        LuaThread t = RUNNING.get();
        return t != null ? t : main();
    }

    public static void setRunning(LuaThread t) {
        RUNNING.set(t);
    }
}
