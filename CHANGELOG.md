# Changelog

Newest first. Each release is a drop-in replacement for `mods/ComputerCraft1.63+tomo1.jar`.

## Monitor load test and experimental parallel scheduler (2026-09-28, no new release)

Measurements and an experimental branch. `fixes-1` is unchanged.

**Monitor-heavy load test.** The setup, identical for all three schedulers:
- single player on an AMD Ryzen Threadripper 1920X;
- 24 computers running [`mload`](tests/ingame/mload), each redrawing its own 57×24 advanced
  monitor 20 times a second;
- 4 dashboards;
- no quarry, because one lost its turtles to a WorldEdit `//regen` and the quarry's load swings
  too much anyway.

The work per redraw was multiplied step by step, 3 minutes per step. The raw reports are in
[`tests/results`](tests/results), in the `2026-09-28-monitors-*` files.

Load computers' redraws a minute (out of 1,200) / dashboards' (out of 600) / average wait:

| Step | Original | Reused worker | 8 parallel workers (experimental) |
|---|---|---|---|
| x1 | all / all / 11 ms (45% busy) | all / all / 7 ms (30% busy) | all / all / 4 ms |
| x2 | all / all / 15 ms (62%) | all / all / 11 ms (46%) | all / all / 7 ms |
| x3 | 1,187+ / 595+ / 19 ms (78%) | all / all / 15 ms (60%) | all / all / 9 ms |
| x4 | **edge**: 1,134+ / 572–580 / 23 ms (93%) | all / 596+ / 19 ms (75%) | **edge**: 1,147+ / 582–585 / 12 ms |
| x5 | — | **edge**: 1,083+ / 561–576 / 23 ms (91%) | — |
| x6 | past: 597+ / 398–428 / 37 ms (100%) | past: 654+ / 431–454 / 28 ms (96%) | past: 597+ / 398–410 / 20 ms |
| x8 | — | — | past: 595+ / 397–398 / 33 ms |

At x6, the minute's total events were about 24,800 for the original, 28,200 for the reused worker
and 24,600 for 8 parallel workers. Nothing was dropped in any run: `mload` waits for its own timer,
so an overloaded computer draws less often (down to 10 a second) instead of piling up events.
Every frame drawn was complete and correct, which was checked on screenshots.

**Conclusion:** for turtles, monitors and peripherals, the reused worker is the best setting. The
parallel scheduler only helps pure Lua. For monitor-heavy load, its lock handoffs cost as much as
reusing workers saves.

**Experimental parallel scheduler** (`-Dcc.threads=N`), on the
[`experimental-parallel`](../../tree/experimental-parallel) branch, not released:
- Runs pure Lua from several computers at once. Everything else stays one computer at a time.
- Pure-Lua load test in game: handled 80,000 `burn` units without falling behind or dropping
  anything. That's 3.5× where the original fell over (23,000) and 2.5× where the reused worker did
  (32,000).
- Headless benchmark of real ComputerCraft computers: compute-heavy load up to 3.2× the original.
  No gain for load made of API calls.
- A test that caught a real bug: starting 24 new computers at once gave 15 duplicate IDs when only
  API calls were locked. The final design locks everything except pure Lua, and gives 24 unique IDs.

The branch's `PARALLEL.md` has the design, all results and two ideas for helping API-heavy
programs.

## Load test at the limit (2026-09-28, no new release)

This entry is measurements only. `fixes-1` is unchanged, and the test ran on the `fixes-1` jar with
`-Dcc.profileSeconds=60`.

**Setup:** single player, the same world and build for every run:
- a 16-turtle quarry plus its service turtle and main computer;
- 4 dashboards redrawing 10 times a second;
- 8 computers running [`burn`](tests/ingame/burn), 20 times a second each. Their work per event
  was raised until the original scheduler couldn't keep up.

Only minutes where the quarry was mining full-time are counted, because its load drops when the
fleet moves between chunks. The first 2 minutes after loading the world are left out, since the
server is catching up on its backlog then.

**Original scheduler, `burn` at 23,000 units (~3.7 ms per event), 8 minutes:**

| | |
|---|---|
| Thread busy | 98–99% |
| Average wait | 180–320 ms |
| Worst wait per minute | 3.3–6.4 s |
| Events dropped (queue full) | 165–893 per minute |
| Events handled | ~25,800 a minute |

Every computer fell behind: the load computers got about 920 of their 1,200 events a minute, and
the dashboards about 577 of 600.

**`-Dcc.reuseWorker=true`, same load, 8 minutes:**

| | |
|---|---|
| Thread busy | 86–89% |
| Average wait | 37–54 ms |
| Worst wait per minute | 0.4–1.7 s |
| Events dropped | 0 |
| Events handled | ~27,600 a minute, 7% more |

The load computers got about 1,075 events a minute, and the miners about 1,020 instead of 850.

**Finding the new limit with the reused worker**, 3 minutes per step:

| `burn` units | Event cost | Result |
|---|---|---|
| 29,000 | ~4.4 ms | 96–97% busy, 70–94 ms waits (rising), 0 dropped: at the edge |
| 32,000 | ~4.75 ms | 97–98% busy, 89–237 ms waits, up to 8.7 s, 168–809 dropped a minute: over the limit |

**Conclusion:** the reused worker gives about 12–17% of a core of headroom. It's the right
setting for a busy server, but it doesn't make the single thread any less of a limit.

## fixes-1 (2026-09-27)

First release of this repo. It contains everything from
[tomodachi94/tech-it#16](https://github.com/tomodachi94/tech-it/pull/16) plus the new items below.

**Changed from the original jar:** `TileEntityMonitorRenderer`, `Terminal`, `TileMonitor`,
`ComputerThread` (and its inner classes), `rom/programs/reactor.unwrapped`. The other 1,088 files are
unchanged.

### New in this release (not in tech-it#16)

- **Fix 5, terminal thread race.** All public `Terminal` methods are `synchronized`, which fixes
  `IllegalArgumentException: Empty string not allowed` at `Terminal.writeToNBT` ←
  `TileMonitor.writeDescription`.
  - Offline, [`RaceTest`](tests/offline/RaceTest.java) fails immediately on the original 1.63
    `Terminal` and on the version from tech-it#16. With this change it ran 2.1 million saves
    without errors, and [`TermTest`](tests/offline/TermTest.java) still passes.
  - In game, the crash happened when pasting a WorldEdit build with 5 monitor walls and 22
    computers. With the fix, pasting the same build again didn't crash, in the one attempt made.
- **Fix 6, `ComputerThread`.**
  - Lost-wakeup fix, always on.
  - Profiler, off unless `-Dcc.profileSeconds=N` is set.
  - Reused worker thread, off unless `-Dcc.reuseWorker=true` is set.
  - Offline: [`SchedTest`](tests/offline/SchedTest.java) runs 10,000 tasks from 20 computers in
    order with none lost, in all three modes. [`LoadTest`](tests/offline/LoadTest.java) models a
    server's load. Below 100% of one core, the reused worker roughly halves waits. Above it,
    neither mode keeps up.
  - In-game load test: 16 mining turtles, a service turtle, a main computer and 4 dashboards
    redrawing 10 times a second, 10 minutes each:

    | | Original scheduler | Reused worker |
    |---|---|---|
    | Thread busy | 56–64% | 31–36% |
    | Average wait | 16–30 ms | 7–10 ms |
    | Miner event cost | 1.3–1.5 ms | 1.15–1.3 ms |
    | Dropped events | 0 | 0 |

    Not all of the drop in "thread busy" is the reused worker. In the first run the main computer
    used 17% of the thread, and in later runs under 2%, for reasons not yet known. Leaving it out,
    the reused worker saves about 15%. Occasional 600–900 ms spikes happen with both, when a
    single normally-1 ms event takes that long. That points at whole-game pauses such as garbage
    collection or saving, not the scheduler.

### Also included, from tech-it#16

- **Fix 1**, monitor rendering with OptiFine. Tested in game on the server.
- **Fix 2**, monitor text length crashes. Tested offline, and in use since.
- **Fix 3**, reactor program crash handler (tech-it#14). Tested with a headless simulation.
- **Fix 4**, damaged monitor walls. Tested with a simulation, the strict verifier, and in game
  with advanced monitors, WorldEdit and single player:
  - **A:** build a 3×1 wall, copy its left 2 blocks, paste them, and break the right-hand pasted
    block. The original always crashes. With the fix, no crash.
  - **B:** build a 5×3 wall, copy its left 3×3 part, and paste it. Break the middle and then the
    left block of the pasted top row, then place a monitor to the right of the remaining top-right
    block. With the fix, no crash.

## Earlier pushes (tomodachi94/tech-it#16, branch `fix-computercraft-monitors`)

Kept here so the history isn't lost when the PR branch is rewritten.

| Date (2026) | Commit | What |
|---|---|---|
| 09-27 01:18 | `cf4a543` | Fixes 1 and 2: monitor rendering and terminal line lengths. An earlier amend, `d25bc08`, was replaced by this. |
| 09-27 16:04 | `a7e6e91` | Fix 3: reactor program crash handler (tech-it#14). |
| 09-27 22:20 | `c053314` | Fix 4, first version: `contract()` only. It still crashed in rare cases when placing next to a damaged wall. |
| 09-27 22:38 | `3461b88` | Fix 4, second part: the merge methods. |
| 09-27 22:51 | `4428545` | `c053314` and `3461b88` squashed into one commit. This is the current state of the PR. |

Fix 5 (`8161d18`, the `Terminal` race) was committed locally for the PR but never pushed there.
It's released here instead.
