#!/usr/bin/env bash
# Builds the jar from the original ComputerCraft1.63+tomo1.jar. See BUILDING.md.
# On the experimental-parallel branch this also includes the parallel scheduler (-Dcc.threads=N):
# see PARALLEL.md.
#
# Needs, in tools/ (not in the repo):
#   ecj.jar              Eclipse compiler (any version that can target Java 6)
#   asm.jar, asm-tree.jar  ObjectWeb ASM 4.x or later
#   mc-1.6.4-srg.jar     Minecraft 1.6.4 client+server with SRG method names (see BUILDING.md)
#   forge-srg.jar        Forge 9.11.1.965 universal with SRG names
#   lwjgl.jar            LWJGL 2.9.x (OpenGL, for the monitor renderer)
#   ComputerCraft1.63+tomo1.jar  the original jar
# Usage: ./build.sh [output.jar]
set -euo pipefail
cd "$(dirname "$0")"
OUT="${1:-build/ComputerCraft1.63+tomo1+fixes-parallel.jar}"
T=tools
ORIG="$T/ComputerCraft1.63+tomo1.jar"
SEP=":"; case "$(uname -s)" in MINGW*|MSYS*|CYGWIN*) SEP=";";; esac
ASM="$T/asm.jar${SEP}$T/asm-tree.jar"
B=build/classes
CP="$B${SEP}build/widened${SEP}$T/mc-1.6.4-srg.jar${SEP}$T/forge-srg.jar${SEP}$T/lwjgl.jar${SEP}$ORIG"
rm -rf build/classes build/tools build/orig build/widened && mkdir -p "$B" build/tools build/orig build/widened

java -jar "$T/ecj.jar" -1.6 -nowarn -cp "$ASM" -d build/tools \
  patchers/AccessWiden.java patchers/TileMonitorPatch.java patchers/JarPatch.java \
  patchers/LuaThreadPatch.java patchers/LuaMachinePatch.java

# 0. Compile-time copy of TileEntity with worldObj (field_70331_k) public, as Forge makes it in-game
java -cp "build/tools${SEP}$ASM" AccessWiden "$T/mc-1.6.4-srg.jar" build/widened net/minecraft/tileentity/TileEntity.field_70331_k

# 1. Bytecode patches of original classes
M=dan200/computercraft/shared/peripheral/monitor
L=dan200/computercraft/core/lua
J=org/luaj/vm2
(cd build/orig && unzip -q -o "../../$ORIG" "$M/TileMonitor.class" "$J/LuaThread.class" "$J/LuaThread\$State.class" \
  "$L/LuaJLuaMachine.class" "$L/LuaJLuaMachine\$2.class" "$L/LuaJLuaMachine\$2\$1.class")
mkdir -p "$B/$M" "$B/$J" "$B/$L"
#    fix 4: TileMonitor
java -cp "build/tools${SEP}$ASM" TileMonitorPatch "build/orig/$M/TileMonitor.class" "$B/$M/TileMonitor.class"
#    parallel: LuaJ's running coroutine per Java thread, and the world lock hooks in the Lua machine
java -cp "build/tools${SEP}$ASM" LuaThreadPatch "build/orig/$J/LuaThread.class" "build/orig/$J/LuaThread\$State.class" "$B/$J"
java -cp "build/tools${SEP}$ASM" LuaMachinePatch "build/orig/$L/LuaJLuaMachine.class" "build/orig/$L/LuaJLuaMachine\$2.class" \
  "build/orig/$L/LuaJLuaMachine\$2\$1.class" "$B/$L"

# 2. Java sources: Terminal (fixes 2, 5), TileEntityMonitorRenderer (fix 1), ComputerThread (fix 6
#    and the parallel scheduler), WorldLock and LuaThreadLocals (parallel). Compiled against the
#    patched classes above.
java -jar "$T/ecj.jar" -1.6 -nowarn -cp "$CP" -d "$B" \
  src/patched/Terminal.java src/patched/TileEntityMonitorRenderer.java src/patched/ComputerThread.java \
  src/patched/WorldLock.java src/patched/LuaThreadLocals.java

# 3. Put it together: replace changed classes and the reactor program, drop the original
#    ComputerThread's second anonymous class (the rewrite doesn't have it)
args=()
while IFS= read -r f; do args+=("${f#$B/}=$f"); done < <(find "$B" -name '*.class' | sort)
args+=("assets/computercraft/lua/rom/programs/reactor.unwrapped=src/lua/reactor.unwrapped")
args+=('dan200/computercraft/core/computer/ComputerThread$1$1.class=DELETE')
mkdir -p "$(dirname "$OUT")"
java -cp build/tools JarPatch "$ORIG" "$OUT" "${args[@]}"
echo "built $OUT"
