# Experimental: running computers in parallel (`-Dcc.threads=N`)

**Status:** experimental, not in a release. It works in every test below, but it's the riskiest
change here, and for the load most servers have (turtles, monitors, peripherals) the plain
`-Dcc.reuseWorker=true` from `fixes-1` does as well or better. See [Results](#results).

## The problem

ComputerCraft 1.63 runs **every computer on the server on one thread**, one event at a time. When
all computers together need more than one CPU core, every computer slows down together: timers
fire late, turtles hesitate, screens update less often, and past a point events are thrown away.
How many cores the server has doesn't matter.

## The rule: nothing a player can observe changes

Any sequence of actions the parallel scheduler produces must be one the original single thread
could also have produced. Only the timing may change.

- **Only pure Lua runs in parallel:** a computer's own maths, tables, strings and control flow.
  Other computers can't observe it.
- **Everything else stays one computer at a time**, behind one "world lock":
  - every API call: `term`, `fs`, `peripheral`, `rednet` through modems, `redstone`, `turtle`, `http`, …;
  - all of ComputerCraft's own Java work: starting and stopping computers, handing out computer
    IDs, peripherals attaching.
- **Once a computer has touched anything in an event, it keeps the world to itself until that
  event ends.** So, as in the original, no other computer's actions can happen between two of its
  actions in the same event. Two computers doing "read a shared file, change it, write it back" can
  never overwrite each other's change.
- **Each computer's own events still run one at a time and in order.** Computers take turns in the
  same round-robin order as the original.
- **Rednet broadcasts reach every computer in range**, exactly as before. Sending runs the same code
  under the lock. Nobody is skipped.

## Design

| Piece | File | What it does |
|---|---|---|
| Worker pool | [`src/patched/ComputerThread.java`](src/patched/ComputerThread.java) (`Pool`, `PoolWorker`) | N long-lived workers. A computer with work is queued once. A worker takes it, runs **one** of its tasks, and puts it back at the end of the queue if it has more. |
| World lock | [`src/patched/WorldLock.java`](src/patched/WorldLock.java) | Owned by a **computer**, not a Java thread, because a computer's API calls run on its Lua coroutine thread while its task runs on a worker. It's first come, first served (a ticket queue), so no computer can be starved. |
| Lock hooks in the Lua machine | [`patchers/LuaMachinePatch.java`](patchers/LuaMachinePatch.java) (bytecode) | Releases the lock just before a computer's Lua is resumed, and takes it at every Java API call, when an API call resumes after waiting for an event (such as a turtle move), and when Lua hands control back. |
| LuaJ | [`patchers/LuaThreadPatch.java`](patchers/LuaThreadPatch.java) (bytecode) + [`src/patched/LuaThreadLocals.java`](src/patched/LuaThreadLocals.java) | LuaJ 2.0.3 keeps "the running coroutine" in one static field, and every Lua function call reads it. Each coroutine already has its own Java thread, so it's now tracked per Java thread. This gives the same answers with one computer, and keeps computers apart with several. |
| Watchdog | `PoolWorker.check()` | The original's limits: 5 s, then a soft abort ("Too long without yielding"), +1.25 s a hard abort, +1.25 s the worker is stopped and replaced. **Time spent waiting for the world lock doesn't count**, so no program is aborted for waiting on another computer. A stopped worker's lock is released. |

The lock sequence for one task:

```
task starts ............... take the lock           (ComputerCraft's Java code, one at a time)
Lua resumes ............... let it go               (pure Lua, in parallel with other computers)
Lua calls a Java API ...... take it, and keep it    (term, fs, peripheral, rednet, turtle, ...)
Lua hands back control .... take it (if not held)
task ends ................. let it go
```

Without `-Dcc.threads` (or with `-Dcc.threads=1`), none of this is active and the scheduler
behaves as in `fixes-1`. `-Dcc.threads=N` includes what `-Dcc.reuseWorker=true` does, and that
setting is ignored.

## How it was tested

**1. A headless harness that runs real ComputerCraft computers** without Minecraft:
[`tests/harness/HeadlessCC.java`](tests/harness/HeadlessCC.java).
- It uses the jar's own bios, ROM and LuaJ.
- Each computer runs a timer-driven work loop and keeps a checksum of its work, which the harness
  recomputes in Java. A corrupted Lua state shows up as a wrong answer, not just a crash.
- Two workloads:
  - `compute`: the Lua work first, then API calls;
  - `dash`: a screen write after each of 19 lines, so API calls are spread through the event.
- The matrix script [`tests/harness/matrix.ps1`](tests/harness/matrix.ps1) runs every setting ×
  workload × load and records the CPU's actual clock during each run.

**2. In game**, in single player, using the load programs in [`tests/ingame`](tests/ingame):
- `burn`: adjustable pure-Lua work;
- `mload`: redraws its own monitor, almost all monitor calls;
- `dash`: a dashboard.

## What the testing found and fixed

- **Starvation:** the first world lock woke every waiting computer and let any of them take it. A
  computer that released and immediately asked again usually won, and some computers got nothing
  for 30 s. Fixed with the ticket queue.
- **Duplicate computer IDs:** the first design only locked API calls. ComputerCraft's own startup
  code, which hands out new IDs, ran in parallel. Starting 24 new computers at once gave **15
  duplicate IDs**, so two computers would share one drive. Fixed by locking everything except pure
  Lua ([`tests/results/2026-09-28-duplicate-id-test.txt`](tests/results/2026-09-28-duplicate-id-test.txt)).

## Results

All on an AMD Ryzen Threadripper 1920X (12 cores / 24 threads, 3.5 GHz base, 3.16–3.39 GHz measured
during runs), Java 1.8.0_51. Raw data is in [`tests/results`](tests/results).

**Headless, 24 computers, events handled per second** (max 480). Every run had all checksums
correct and no computer missing.

| Setting | compute light / medium / heavy | dash light / medium / heavy |
|---|---|---|
| original | 420 / 241 / 135 | 322 / 224 / 123 |
| reuse worker | 424 / 259 / 136 | 395 / 227 / 125 |
| 2 threads | 433 / 398 / 236 | 395 / 224 / 136 |
| 4 threads | 440 / 430 / 363 | 367 / 224 / 134 |
| 8 threads | 439 / 433 / 416 | 386 / 233 / 131 |
| 16 threads | 448 / 441 / **428** | 391 / 241 / 135 |

**In game, pure Lua** (quarry + 4 dashboards + 8 `burn` computers; see
[`2026-09-28-burn-8-threads.log`](tests/results/2026-09-28-burn-8-threads.log)):
- The original fell over at 23,000 units: 99% busy, 180–320 ms waits and 165–890 events dropped a
  minute.
- The reused worker fell over at 32,000.
- **8 workers took 80,000** (3.5×) with every computer at full speed, average waits of about 11 ms
  and nothing dropped.

**In game, monitor-heavy** (24 computers redrawing 57×24 monitors + 4 dashboards). Each cell is
the first step where computers fell behind, and the busy share at x1:

| Original | Reused worker | 8 threads |
|---|---|---|
| x4 (45% busy at x1) | **x5** (30% busy at x1) | x4 |

## Conclusions

- **Pure-Lua-heavy load:** parallel workers are a big win.
- **Load made of API calls** (monitors, peripherals, turtles): no gain over the original. Almost
  everything happens under the world lock, and handing the lock between computers costs about as
  much as reusing the workers saves. **Here the plain reused worker is better.**
- Most real servers' computer load is turtles, reactor screens and dashboards. So
  `-Dcc.reuseWorker=true` is the recommendation, and this stays experimental.

## Ideas for making API-heavy load faster (not done)

1. **Cheaper lock handoffs:** wake only the next computer in the queue instead of everyone, and
   don't release and re-take the lock when nobody is waiting. There's no behaviour change. The
   expected result is to at least match the reused worker on monitor load.
2. **Skip the lock for calls no other computer can observe:**
   - the computer's own screen (`term`), timers and clock (`os.startTimer`, `os.clock`), and `bit`;
   - **a ComputerCraft monitor attached to that computer only**, the usual reactor-screen or
     dashboard setup.

   Shared monitors, modems and rednet, files, redstone, turtles and **other mods' peripherals**
   (their code may not be safe to call from several threads) would keep the lock. A computer
   attaching to a monitor that another computer is using on its own would have to wait until that
   computer's event ends. This could let monitor-heavy programs scale like pure Lua does, but it
   needs careful testing.

## Building and running

`./build.sh` on this branch builds `build/ComputerCraft1.63+tomo1+fixes-parallel.jar` (see
[BUILDING.md](BUILDING.md) for the tools). It's identical in code to the jar used for the in-game
tests above. Add `-Dcc.threads=8` (or your core count) to the server's Java arguments, and
`-Dcc.profileSeconds=60` to see what the computers are doing. In parallel mode, "thread busy" is
summed over all workers and includes time spent waiting for the world lock, so it can go above
100%. Compare events handled, waits and drops instead.

To run the harness, compile `tests/harness/HeadlessCC.java` against the built jar and
`tools/mc-1.6.4-srg.jar`, then run it as `HeadlessCC <jar> <workdir> <computers> <seconds> <units>
<compute|dash>` with the same jars on the classpath, plus `-Dcc.threads=N` or
`-Dcc.reuseWorker=true`. Add `-Dharness.newIds=true` for the duplicate-ID test.
