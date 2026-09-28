# ComputerCraft 1.63 fixes

Crash and performance fixes for **ComputerCraft 1.63** (the `1.63+tomo1` build used by the
[TechIt-ng](https://github.com/tomodachi94/tech-it) modpack, Minecraft 1.6.4 / Forge 9.11.1.965).

The download is a drop-in replacement for `mods/ComputerCraft1.63+tomo1.jar`. Everything not
listed below is byte-for-byte the same as the original jar.

**Download:** see [Releases](../../releases). Each release lists exactly what changed, and
[CHANGELOG.md](CHANGELOG.md) keeps the full history, including fixes that were first sent to the
modpack as [tomodachi94/tech-it#16](https://github.com/tomodachi94/tech-it/pull/16).

## Fixes

| # | Problem | Where | Tested |
|---|---|---|---|
| 1 | Monitor screens disappear with OptiFine, plus an NPE crash rendering a removed monitor | client | in game, on the server |
| 2 | `StringIndexOutOfBoundsException` crashes from monitor text (`Terminal.resize`, `FixedWidthFontRenderer.drawString`) | client and server | offline tests; in use since |
| 3 | The `reactor` program dies with `startup:6: attempt to concatenate string and nil` and stays dead | reactor program | headless simulation of the program |
| 4 | Server crash breaking or placing monitors next to a damaged monitor wall (`NullPointerException at TileMonitor.contract`) | server | simulation, strict verifier, in game |
| 5 | Server crash `Empty string not allowed` from monitor terminals, from a thread race | server | offline race test; pasting a 22-computer build in game |
| 6 | Computer scheduler: lost-wakeup fix, optional profiler, optional reused worker thread | server | offline benchmarks, in-game load tests |

### 1. Monitor screens disappear with OptiFine

`TileEntityMonitorRenderer` only drew a monitor wall's screen from its origin (bottom-left) block.
OptiFine only renders a tile entity when its own chunk section is visible, so the whole screen
vanished when that one block's section was culled, for example when looking up at a tall wall.
Now any block of the wall draws the screen on the origin's behalf, at most once per frame. If the
origin can't be found, the block is skipped instead of crashing
(`NullPointerException at TileMonitor.getTerminal`).

Source: [`src/patched/TileEntityMonitorRenderer.java`](src/patched/TileEntityMonitorRenderer.java)
(original: [`src/original/`](src/original/TileEntityMonitorRenderer.java)).

### 2. Monitor text crashes

`Terminal` assumed every text line is exactly `width` characters and every colour line `2 * width`.
Monitor updates can arrive with other lengths, for example after a resize or rescale, and resizing or rendering
then indexed past the end. A `normaliseLines()` helper pads or trims lines after loading and
before resizing. Correct data is left untouched.

Source: [`src/patched/Terminal.java`](src/patched/Terminal.java). Test:
[`tests/offline/TermTest.java`](tests/offline/TermTest.java).

### 3. Reactor program crash handler

When a peripheral method throws a Java exception without a message (for example a monitor's
`NullPointerException`), ComputerCraft turns it into a Lua error whose value is `nil`. The reactor
program's crash handler then failed on `"..." .. nil`, which hid the real error and left the
program dead. Now it prints `tostring(err)`, waits 10 seconds (Ctrl+T cancels) and reboots, so the
program starts fresh.

Source: [`src/lua/reactor.unwrapped`](src/lua/reactor.unwrapped) (original:
[`reactor.unwrapped.original`](src/lua/reactor.unwrapped.original); only line 6, the minified
program, differs). Test: [`tests/simulation/ReactorSim.java`](tests/simulation/ReactorSim.java)
runs the program on ComputerCraft's own LuaJ with a fake reactor and monitor.

### 4. Damaged monitor walls crash the server

Each monitor block saves its wall's size and its position in the wall. If that doesn't match the
blocks that are actually there (for example after a WorldEdit/Schematica paste of part of a wall),
ComputerCraft assumes neighbours exist that don't:

- **breaking** a block: `contract()` calls `resize()` on missing neighbours;
- **placing** a block: `mergeLeft/Right/Up/Down()` call `getOrigin().resize()` on a wall whose
  origin is missing.

Both now skip what isn't there. This is a bytecode patch of `TileMonitor.class`, made by
[`patchers/TileMonitorPatch.java`](patchers/TileMonitorPatch.java). A decompiled view of the result is in
[`src/patched/TileMonitor.decompiled.java`](src/patched/TileMonitor.decompiled.java).

The wall logic, transcribed into [`tests/simulation/MonSim*.java`](tests/simulation/), gave these results:

| Scenario | Original | Fixed |
|---|---|---|
| 400,000 normal build/break sequences | 0 crashes | 0 crashes, same results |
| 300,000 pasted walls with random blocks missing | 96,322 crashes | 0 crashes, no infinite loops |
| 1,000,000 pasted lower-left parts of a wall | 227,092 crashes | 0 crashes |

In game, both of the simplest crash cases were tried and didn't crash. The recipes are in [CHANGELOG.md](CHANGELOG.md).

### 5. `Empty string not allowed` (terminal thread race)

A computer changes its terminal on the ComputerCraft thread while the server thread saves and
sends it. `resize()` and `scroll()` swap in new, empty line arrays and fill them in afterwards, so
the server could read a `null` line and crash writing it to NBT. The original 1.63 `Terminal` has
this race. All public `Terminal` methods are now `synchronized`, as in later ComputerCraft versions.

[`tests/offline/RaceTest.java`](tests/offline/RaceTest.java) runs one thread resizing and one
saving:

- **Original `Terminal`:** fails immediately, every run.
- **Fixed `Terminal`:** over 2 million saves, no errors.

### 6. Computer scheduler (`ComputerThread`)

ComputerCraft runs every computer's Lua on **one** thread, one event at a time. Source:
[`src/patched/ComputerThread.java`](src/patched/ComputerThread.java) (original:
[`src/original/ComputerThread.java`](src/original/ComputerThread.java)).

- **Always on: lost-wakeup fix.** The original checked for work outside the lock it waits on, so a
  task queued at the wrong moment could wait until the next one arrived.
- **Optional: profiler**, `-Dcc.profileSeconds=300` in the server's Java arguments. Every N
  seconds it writes a `[CC-Profile]` report to the log:
  - how busy the computer thread was;
  - how long events waited;
  - events dropped because a computer's queue (256) was full;
  - every busy computer (ID and label) with its share of the thread and its longest task.

  This is the way to find which programs are slowing a server down.
- **Optional: reused worker**, `-Dcc.reuseWorker=true`. ComputerCraft 1.63 starts a new Java
  thread for every single event, about 20,000 a minute with one busy quarry. This option keeps one
  worker thread, replacing it only if a computer has to be forcibly stopped. In game, with 16
  mining turtles and 4 fast dashboards:
  - each event was about 15% cheaper;
  - average waits went from 16–30 ms to 7–10 ms;
  - the thread went from about 57% busy to about 35% (part of that drop was an unrelated change in one computer's load, see CHANGELOG).

Without these Java arguments, scheduling works exactly like the original, apart from the
lost-wakeup fix.

#### Recommendation for servers: turn on `-Dcc.reuseWorker=true`

All numbers below are from single-player on an AMD Ryzen Threadripper 1920X (12 cores, 3.5 GHz
base) with Java 1.8.0_51. Faster or slower CPUs move the limits, but not the comparisons.

A load test pushed a single-player world to the point where the computer thread can't keep up,
then compared both schedulers under exactly the same load. The load was a 16-turtle quarry, 4
dashboards and 8 computers running adjustable CPU work
([`tests/ingame/burn`](tests/ingame/burn)).

| Extra load per load computer | Original scheduler | `-Dcc.reuseWorker=true` |
|---|---|---|
| ~3.7 ms × 20/s | **overloaded**: 99% busy, 180–320 ms average wait, up to 6 s, 165–890 events dropped a minute | fine: 87% busy, ~40 ms average wait, 0 dropped |
| ~4.4 ms × 20/s | overloaded | at the edge: 97% busy, 70–94 ms, 0 dropped |
| ~4.75 ms × 20/s | overloaded | **overloaded**: 98% busy, 135–237 ms, up to 8.7 s, 168–809 dropped a minute |

Starting a Java thread for every event costs about 0.2–0.3 ms. At 26,000 events a minute that's
10–12% of a core. Removing it lets a server take roughly **12–17% of a core more** Lua work
before it falls over. That's about half a quarry, or a dozen more reactor programs. It moves
the limit, but doesn't remove it. Everything still runs on one thread.

"Dropped" means ComputerCraft threw events away because a computer already had 256 waiting. In
game that shows up as programs missing timer, rednet or turtle events, not just running slowly.

A second test used **monitor-heavy** load, the kind reactor screens and dashboards create:
- 24 computers, each redrawing a 57×24 monitor 20 times a second;
- 4 dashboards;
- no quarry.

Each step multiplies the monitor work (see [`tests/ingame/mload`](tests/ingame/mload)). Each cell
shows the load computers' redraws a minute (out of 1,200), the dashboards' redraws (out of 600),
and the average wait:

| Monitor work | Original scheduler | `-Dcc.reuseWorker=true` |
|---|---|---|
| x1 | all served, 11 ms, **45% busy** | all served, 7 ms, **30% busy** |
| x2 | all served, 15 ms, 62% busy | all served, 11 ms, 46% busy |
| x3 | all served, 19 ms, 78% busy | all served, 15 ms, 60% busy |
| x4 | **edge**: 1,134–1,200 / 572–580, 23 ms, 93% busy | all served, 19 ms, 75% busy |
| x5 | past the edge | **edge**: 1,083–1,200 / 561–576, 23 ms, 91% busy |
| x6 | past: 597–1,199 / 398–428, 37 ms | past: 654–1,200 / 431–454, 28 ms, 14% more work done |

Here the reused worker needs about **a third less of the thread** for the same work, and handles
one step more before falling behind.

**Which setting for which programs:**

| Most of the server's computer load is… | Example | Best setting |
|---|---|---|
| Turtles | quarries, farms | `-Dcc.reuseWorker=true` (one 16-turtle quarry went from ~57% to ~35% of the thread) |
| Monitors and peripherals | reactor screens, dashboards | `-Dcc.reuseWorker=true` |
| Heavy pure-Lua work | big calculations, pathfinding | parallel workers (experimental, see below) |

**Experimental: several computers at once.** The
[`experimental-parallel`](../../tree/experimental-parallel) branch runs computers on several
threads, with `-Dcc.threads=N`.
- Only pure Lua runs in parallel. Everything that touches the world, files, peripherals or other
  computers still happens one computer at a time, so programs behave exactly as before.
- For pure-Lua load it handled **3.5× the load** of the original.
- For monitor-heavy load it reached its limit at the same point as the original. The reused
  worker does better there.

It isn't in a release. The branch's `PARALLEL.md` has the design, all measurements and what's
left to do.

Turtle actions (digging, moving) also do their real work on Minecraft's main server thread. Many
busy turtles cost server TPS whatever the scheduler setting.

## Installing

Replace `mods/ComputerCraft1.63+tomo1.jar` with the release jar, on the server and on clients. The
file name doesn't matter to Forge, but keeping the original name avoids duplicates when the pack
updates. Fixes 1 and 2 matter on clients. Fixes 2 to 6 matter on the server.

## How it's built

See [BUILDING.md](BUILDING.md). The Java sources in `src/original` are decompiled from the
original jar (CFR) for comparison. The ones in `src/patched` are what the release classes are
compiled from.

## Credits

ComputerCraft is by Daniel Ratcliffe (dan200). All rights to ComputerCraft stay with its authors.
This project only changes a few of its classes, for use in a non-commercial modpack, and will be
taken down on request. The `1.63+tomo1` base and
the reactor program are from Tomodachi94's TechIt-ng pack.
