import java.io.*;
import java.util.Random;
import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.JsePlatform;

/**
 * Runs the reactor program on ComputerCraft 1.63's own LuaJ with a fake Big Reactors reactor.
 * args: programFile simSeconds scenario seed
 * scenarios: none | npe | disconnect | npe-random | clicks
 */
public class ReactorSim {
    static String scenario;
    static double failAt;
    static Random rnd;
    static LuaValue clockFn;

    static double now() { return clockFn.call().todouble(); }

    // Mirrors LuaJLuaMachine.wrapLuaObject: Java exceptions become LuaError(t.getMessage()).
    static abstract class Method extends VarArgFunction {
        final String name;
        Method(String name) { this.name = name; }
        abstract Object[] call(Varargs args) throws Exception;
        public Varargs invoke(Varargs args) {
            Object[] results;
            try {
                results = call(args);
            } catch (LuaError e) {
                throw e;
            } catch (Throwable t) {
                throw new LuaError(t.getMessage());
            }
            LuaValue[] vals = new LuaValue[results.length];
            for (int i = 0; i < results.length; i++) {
                Object o = results[i];
                if (o instanceof Boolean) vals[i] = LuaValue.valueOf((Boolean) o);
                else if (o instanceof Integer) vals[i] = LuaValue.valueOf((Integer) o);
                else if (o instanceof Number) vals[i] = LuaValue.valueOf(((Number) o).doubleValue());
                else if (o instanceof String) vals[i] = LuaValue.valueOf((String) o);
                else vals[i] = LuaValue.NIL;
            }
            return LuaValue.varargsOf(vals);
        }
    }

    static boolean active = true;
    static double energy = 5000000, fuel = 30000, rods = 30;

    static void maybeFail(String method) throws Exception {
        double t = now();
        if (scenario.equals("npe") && t >= failAt) throw new NullPointerException();
        if (scenario.equals("disconnect") && t >= failAt) throw new Exception("Unable to access reactor - port is not connected");
        if (scenario.equals("npe-random") && rnd.nextDouble() < 0.0005) throw new NullPointerException();
    }

    // ComputerCraft 1.63 MonitorPeripheral calls TileMonitor.getTerminal(), which throws a
    // NullPointerException (no message) when the monitor wall has no origin.
    static void maybeFailMonitor() throws Exception {
        if (scenario.equals("monitor-npe") && now() >= failAt) throw new NullPointerException();
        if (scenario.equals("monitor-flaky") && now() >= failAt && now() < failAt + 5) throw new NullPointerException();
    }

    static Method m(String name, final Object value) {
        return new Method(name) {
            Object[] call(Varargs a) throws Exception {
                maybeFail(name);
                return new Object[]{ value instanceof Double ? (Object) ((Double) value + rnd.nextDouble() * 10) : value };
            }
        };
    }

    public static void main(String[] args) throws Exception {
        String program = args[0];
        double simSeconds = Double.parseDouble(args[1]);
        scenario = args[2];
        long seed = Long.parseLong(args[3]);
        rnd = new Random(seed);
        failAt = 60 + (seed % 7) * 600;

        LuaTable g = JsePlatform.standardGlobals();
        LuaTable reactor = new LuaTable();
        reactor.set("getActive", new Method("getActive") { Object[] call(Varargs a) throws Exception { maybeFail(name); return new Object[]{active}; } });
        reactor.set("setActive", new Method("setActive") { Object[] call(Varargs a) throws Exception { maybeFail(name); active = a.arg1().toboolean(); return new Object[0]; } });
        reactor.set("getEnergyStored", new Method("getEnergyStored") { Object[] call(Varargs a) throws Exception { maybeFail(name); energy = Math.max(0, Math.min(10000000, energy + (rnd.nextDouble() - 0.5) * 200000)); return new Object[]{(int) energy}; } });
        reactor.set("getFuelAmountMax", m("getFuelAmountMax", 46000));
        reactor.set("getFuelAmount", new Method("getFuelAmount") { Object[] call(Varargs a) throws Exception { maybeFail(name); return new Object[]{(int) fuel}; } });
        reactor.set("getFuelTemperature", m("getFuelTemperature", 700.0));
        reactor.set("getCasingTemperature", m("getCasingTemperature", 650.0));
        reactor.set("getEnergyProducedLastTick", m("getEnergyProducedLastTick", 2400.0));
        reactor.set("getControlRodLevel", new Method("getControlRodLevel") { Object[] call(Varargs a) throws Exception { maybeFail(name); return new Object[]{(int) rods}; } });
        reactor.set("getNumberOfControlRods", m("getNumberOfControlRods", 9));
        reactor.set("setAllControlRodLevels", new Method("setAllControlRodLevels") { Object[] call(Varargs a) throws Exception { maybeFail(name); rods = a.arg1().todouble(); return new Object[0]; } });
        reactor.set("getConnected", m("getConnected", Boolean.TRUE));
        g.set("__reactor", reactor);
        if (scenario.startsWith("monitor")) {
            LuaTable mon = new LuaTable();
            String[] noop = {"setCursorPos", "write", "clear", "setBackgroundColour", "setBackgroundColor", "setTextColour", "setTextColor", "setTextScale", "clearLine", "setCursorBlink"};
            for (final String n : noop) {
                mon.set(n, new Method(n) { Object[] call(Varargs a) throws Exception { maybeFailMonitor(); return new Object[0]; } });
            }
            mon.set("getSize", new Method("getSize") { Object[] call(Varargs a) throws Exception { maybeFailMonitor(); return new Object[]{36, 24}; } });
            mon.set("isColour", m("isColour", Boolean.TRUE));
            mon.set("isColor", m("isColor", Boolean.TRUE));
            g.set("__monitor", mon);
        }
        g.set("__simSeconds", LuaValue.valueOf(simSeconds));
        g.set("__clicks", LuaValue.valueOf(scenario.equals("clicks")));
        g.set("__println", new OneArgFunction() {
            public LuaValue call(LuaValue s) { System.out.println("  | " + s.tojstring()); return LuaValue.NIL; }
        });

        LoadState.load(new FileInputStream("mocks.lua"), "mocks", g).call();
        clockFn = g.get("os").get("clock");

        long start = System.currentTimeMillis();
        String outcome;
        try {
            int reboots = 0;
            while (true) {
                g.set("__prog", LoadState.load(new FileInputStream(program), "startup", g));
                // CraftOS runs programs with pcall and prints the error string, so do the same
                Varargs r = g.get("pcall").invoke(LuaValue.varargsOf(new LuaValue[]{ g.get("__prog") }));
                if (!r.arg1().toboolean() && r.arg(2).tojstring().contains("__REBOOT__")) {
                    reboots++;
                    System.out.println("  | --- computer rebooted at " + String.format("%.0f", now()) + "s, running startup again ---");
                    continue;
                }
                outcome = (r.arg1().toboolean() ? "program exited" : "UNCAUGHT ERROR shown on screen: " + r.arg(2).tojstring()) + ", reboots: " + reboots;
                break;
            }
        } catch (LuaError e) {
            // What CraftOS's shell shows for an uncaught error
            outcome = "UNCAUGHT ERROR shown on screen: " + e.getMessage();
        }
        System.out.println(String.format("[%s seed=%d] simulated %.0fs (%.1f h) in %d ms -> %s",
            scenario, seed, now(), now() / 3600, System.currentTimeMillis() - start, outcome));
    }
}
