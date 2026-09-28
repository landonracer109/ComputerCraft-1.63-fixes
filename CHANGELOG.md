# Changelog

Newest first. Each release is a drop-in replacement for `mods/ComputerCraft1.63+tomo1.jar`.

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
