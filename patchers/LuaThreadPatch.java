import java.io.*; import java.util.*;
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
// args: LuaThread.class LuaThread$State.class outDir
// LuaThread: static running_thread/main_thread reads and writes go through LuaThreadLocals, and a
// static newMainThread() factory is added (the no-argument constructor is private).
// LuaThread$State.run(): first marks its LuaThread as running on the coroutine's own Java thread.
public class LuaThreadPatch {
  static final String LT = "org/luaj/vm2/LuaThread", TL = "org/luaj/vm2/LuaThreadLocals", D = "L" + LT + ";";
  public static void main(String[] a) throws Exception {
    ClassNode cn = read(a[0]); int n = 0;
    for (MethodNode m : (List<MethodNode>) cn.methods)
      for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
        if (!(i instanceof FieldInsnNode)) continue;
        FieldInsnNode f = (FieldInsnNode) i;
        if (!f.owner.equals(LT) || !(f.name.equals("running_thread") || f.name.equals("main_thread"))) continue;
        boolean run = f.name.equals("running_thread");
        AbstractInsnNode r;
        if (f.getOpcode() == Opcodes.GETSTATIC) r = new MethodInsnNode(Opcodes.INVOKESTATIC, TL, run ? "running" : "main", "()" + D);
        else if (f.getOpcode() == Opcodes.PUTSTATIC) r = new MethodInsnNode(Opcodes.INVOKESTATIC, TL, run ? "setRunning" : "setMain", "(" + D + ")V");
        else continue;
        m.instructions.set(i, r); i = r; n++;
      }
    MethodNode f = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "newMainThread", "()" + D, null, null);
    f.instructions.add(new TypeInsnNode(Opcodes.NEW, LT));
    f.instructions.add(new InsnNode(Opcodes.DUP));
    f.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, LT, "<init>", "()V"));
    f.instructions.add(new InsnNode(Opcodes.ARETURN));
    f.maxStack = 2; f.maxLocals = 0; cn.methods.add(f);
    if (n != 14) throw new RuntimeException("expected 14 field accesses, patched " + n);
    write(cn, a[2] + "/LuaThread.class");

    ClassNode st = read(a[1]); boolean done = false;
    for (MethodNode m : (List<MethodNode>) st.methods) {
      if (!m.name.equals("run") || !m.desc.equals("()V")) continue;
      InsnList p = new InsnList();
      p.add(new VarInsnNode(Opcodes.ALOAD, 0));
      p.add(new FieldInsnNode(Opcodes.GETFIELD, LT + "$State", "lua_thread", "Ljava/lang/ref/WeakReference;"));
      p.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ref/WeakReference", "get", "()Ljava/lang/Object;"));
      p.add(new TypeInsnNode(Opcodes.CHECKCAST, LT));
      p.add(new MethodInsnNode(Opcodes.INVOKESTATIC, TL, "setRunning", "(" + D + ")V"));
      m.instructions.insert(p); m.maxStack = Math.max(m.maxStack, 1); done = true;
    }
    if (!done) throw new RuntimeException("State.run not found");
    write(st, a[2] + "/LuaThread$State.class");
    System.out.println("LuaThread: " + n + " static accesses now per-thread; State.run sets its coroutine as running");
  }
  static ClassNode read(String p) throws IOException { ClassNode c = new ClassNode(); new ClassReader(new FileInputStream(p)).accept(c, 0); return c; }
  static void write(ClassNode c, String p) throws IOException { ClassWriter w = new ClassWriter(0); c.accept(w); new File(p).getParentFile().mkdirs(); FileOutputStream o = new FileOutputStream(p); o.write(w.toByteArray()); o.close(); }
}
