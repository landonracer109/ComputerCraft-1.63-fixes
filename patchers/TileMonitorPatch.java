import java.io.*; import java.util.*;
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
// args: in TileMonitor.class (original), out TileMonitor.class
// 1. contract(): its six resize() calls on neighbours go through safeResize(), which skips a
//    neighbour that isn't there (the original throws a NullPointerException).
// 2. mergeLeft/Right/Up/Down(): "X.getOrigin().resize(w, h); Y.expand(); return true;" becomes
//    "return mergeInto(X.getOrigin(), w, h, Y);", which doesn't merge (returns false) when the
//    wall's origin block is missing. No branches are added to the existing methods.
public class TileMonitorPatch {
  static final String OWNER = "dan200/computercraft/shared/peripheral/monitor/TileMonitor";
  static final String T = "L" + OWNER + ";";
  static AbstractInsnNode real(AbstractInsnNode n) { while (n != null && n.getOpcode() < 0) n = n.getNext(); return n; }
  public static void main(String[] a) throws Exception {
    ClassNode cn = new ClassNode();
    new ClassReader(new FileInputStream(a[0])).accept(cn, 0);
    int contractCalls = 0, merges = 0;
    for (MethodNode m : (List<MethodNode>) cn.methods) {
      if (m.name.equals("contract") && m.desc.equals("()V")) {
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
          if (n instanceof MethodInsnNode && isResize((MethodInsnNode) n)) {
            MethodInsnNode rep = new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "safeResize", "(" + T + "II)V");
            m.instructions.set(n, rep); n = rep; contractCalls++;
          }
        }
      }
      if (m.name.startsWith("merge") && m.desc.equals("()Z")) {
        int found = 0;
        for (AbstractInsnNode n = m.instructions.getFirst(); n != null; n = n.getNext()) {
          if (!(n instanceof MethodInsnNode) || !isResize((MethodInsnNode) n)) continue;
          AbstractInsnNode load = real(n.getNext());
          AbstractInsnNode expand = real(load.getNext());
          AbstractInsnNode one = real(expand.getNext());
          AbstractInsnNode ret = real(one.getNext());
          if (load.getOpcode() != Opcodes.ALOAD || !(expand instanceof MethodInsnNode) || !((MethodInsnNode) expand).name.equals("expand")
              || one.getOpcode() != Opcodes.ICONST_1 || ret.getOpcode() != Opcodes.IRETURN) throw new RuntimeException("unexpected code in " + m.name);
          AbstractInsnNode next = n.getNext();
          m.instructions.remove(n);                   // origin, w, h stay on the stack
          m.instructions.set(expand, new MethodInsnNode(Opcodes.INVOKESTATIC, OWNER, "mergeInto", "(" + T + "II" + T + ")Z"));
          m.instructions.remove(one);                 // IRETURN now returns mergeInto's result
          m.maxStack = Math.max(m.maxStack, 4);
          found++; n = next;
        }
        if (found != 1) throw new RuntimeException(m.name + ": expected 1 merge, found " + found);
        merges++;
      }
    }
    if (contractCalls != 6 || merges != 4) throw new RuntimeException("contract calls " + contractCalls + ", merges " + merges);
    // private static void safeResize(TileMonitor m, int w, int h) { if (m != null) m.resize(w, h); }
    MethodNode s = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "safeResize", "(" + T + "II)V", null, null);
    LabelNode skip = new LabelNode();
    s.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    s.instructions.add(new JumpInsnNode(Opcodes.IFNULL, skip));
    s.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    s.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
    s.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
    s.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OWNER, "resize", "(II)V"));
    s.instructions.add(skip);
    s.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    s.instructions.add(new InsnNode(Opcodes.RETURN));
    s.maxStack = 3; s.maxLocals = 3;
    cn.methods.add(s);
    // private static boolean mergeInto(TileMonitor origin, int w, int h, TileMonitor m) {
    //   if (origin == null) return false; origin.resize(w, h); m.expand(); return true; }
    MethodNode g = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "mergeInto", "(" + T + "II" + T + ")Z", null, null);
    LabelNode ok = new LabelNode();
    g.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    g.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, ok));
    g.instructions.add(new InsnNode(Opcodes.ICONST_0));
    g.instructions.add(new InsnNode(Opcodes.IRETURN));
    g.instructions.add(ok);
    g.instructions.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
    g.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
    g.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
    g.instructions.add(new VarInsnNode(Opcodes.ILOAD, 2));
    g.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OWNER, "resize", "(II)V"));
    g.instructions.add(new VarInsnNode(Opcodes.ALOAD, 3));
    g.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, OWNER, "expand", "()V"));
    g.instructions.add(new InsnNode(Opcodes.ICONST_1));
    g.instructions.add(new InsnNode(Opcodes.IRETURN));
    g.maxStack = 3; g.maxLocals = 4;
    cn.methods.add(g);
    ClassWriter cw = new ClassWriter(0);
    cn.accept(cw);
    FileOutputStream o = new FileOutputStream(a[1]); o.write(cw.toByteArray()); o.close();
    System.out.println("patched contract (" + contractCalls + " calls) and " + merges + " merge methods");
  }
  static boolean isResize(MethodInsnNode mi) { return mi.getOpcode() == Opcodes.INVOKESPECIAL && mi.owner.equals(OWNER) && mi.name.equals("resize") && mi.desc.equals("(II)V"); }
}
