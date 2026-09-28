# Building

`build.sh` builds the release jar from the original `ComputerCraft1.63+tomo1.jar`. It works with
bash on Linux/macOS and Git Bash on Windows, and needs Java 8. The release jar for `fixes-1` was
checked to be byte-for-byte identical to what `build.sh` produces.

## Tools (put them in `tools/`, which is not committed)

| File | What | Where to get it |
|---|---|---|
| `ecj.jar` | Eclipse Java compiler, used to target Java 6 like the original | Maven Central, `org.eclipse.jdt:ecj` (3.x) |
| `asm.jar`, `asm-tree.jar` | ObjectWeb ASM 4 or later, for the bytecode patches | Maven Central, `org.ow2.asm` |
| `lwjgl.jar` | LWJGL 2.9.x, which the monitor renderer calls | any 1.6.4 launcher's libraries |
| `mc-1.6.4-srg.jar` | Minecraft 1.6.4 client and server with **SRG** names (`field_70331_k`, `func_74778_a`, …), the names Forge 1.6.4 uses when the game runs | remap the vanilla 1.6.4 jar with [SpecialSource](https://github.com/md-5/SpecialSource) and `joined.srg` from Forge's `deobfuscation_data-1.6.4.lzma` (an LZMA file inside the Forge universal jar) |
| `forge-srg.jar` | Forge 9.11.1.965 universal, SRG names | the Forge universal jar, remapped the same way if needed |
| `ComputerCraft1.63+tomo1.jar` | the original jar | the TechIt-ng pack |

## What `build.sh` does

0. **Compile-time only:** makes `TileEntity.worldObj` public in a copy of the class, as Forge's
   access transformer does in game ([`patchers/AccessWiden.java`](patchers/AccessWiden.java)).
1. **Compiles** `src/patched/Terminal.java`, `TileEntityMonitorRenderer.java` and
   `ComputerThread.java` with `ecj -1.6` against Minecraft, Forge, LWJGL and the original jar.
2. **Patches** the original `TileMonitor.class` with
   [`patchers/TileMonitorPatch.java`](patchers/TileMonitorPatch.java) (ASM). It fails if the code
   it expects isn't there.
3. **Copies the original jar** with those classes replaced, `rom/programs/reactor.unwrapped`
   replaced by [`src/lua/reactor.unwrapped`](src/lua/reactor.unwrapped), and the original
   `ComputerThread$1$1.class` removed ([`patchers/JarPatch.java`](patchers/JarPatch.java)).

## Tests

| Test | What it checks | How to run |
|---|---|---|
| [`tests/offline/TermTest.java`](tests/offline/TermTest.java) | fix 2 | compile against the built jar and `mc-1.6.4-srg.jar`, run `TermTest` |
| [`tests/offline/RaceTest.java`](tests/offline/RaceTest.java) | fix 5 | same, run `RaceTest 10` (seconds) |
| [`tests/offline/SchedTest.java`](tests/offline/SchedTest.java) | fix 6: ordering and lost tasks | `SchedTest paced` or `SchedTest flood`, with or without `-Dcc.reuseWorker=true` / `-Dcc.profileSeconds=2` |
| [`tests/offline/LoadTest.java`](tests/offline/LoadTest.java) | fix 6: a server's load | `LoadTest dashboards dashMs dashRate turtles turtleMs turtleRate seconds` |
| [`tests/simulation/MonSim*.java`](tests/simulation/) | fix 4: the wall logic transcribed from `TileMonitor` | no dependencies: `MonSim2 paste 300000`, `MonSim3 <0/1/2> 1000000` |
| [`tests/simulation/ReactorSim.java`](tests/simulation/ReactorSim.java) + `mocks.lua` | fix 3 | needs the jar's bundled LuaJ, run `ReactorSim program simSeconds scenario seed` |
| [`tests/ingame/`](tests/ingame/) | load test programs | copy them onto computers in a test world: `dash` redraws a monitor N times a second; `burn` does adjustable CPU work set in `load.cfg` |
