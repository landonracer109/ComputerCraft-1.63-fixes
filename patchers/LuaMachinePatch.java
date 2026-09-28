import java.io.*; import java.util.*;
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
// args: LuaJLuaMachine.class LuaJLuaMachine$2.class LuaJLuaMachine$2$1.class outDir
// Hooks for WorldLock (only active with -Dcc.threads=N, N > 1):
//  handleEvent: WorldLock.beforeResume(this) just before resuming the computer's Lua, and
//               WorldLock.afterResume(this) when it hands control back.
//  $2.invoke (every Java API method called from Lua): WorldLock.apiCall(machine) first.
//  $2$1.yield (an API call that waited for an event, e.g. a turtle move): WorldLock.apiCall(machine)
//               again when it resumes.
public class LuaMachinePatch {
  static final String P = "dan200/computercraft/core/lua/", M = P + "LuaJLuaMachine", A = M + "$2", C = A + "$1";
  static final String WL = P + "WorldLock", OBJ = "(Ljava/lang/Object;)V";
  public static void main(String[] a) throws Exception {
    int n = 0;
    ClassNode mc = read(a[0]);
    for (MethodNode m : (List<MethodNode>) mc.methods) {
      if (!m.name.equals("handleEvent")) continue;
      for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
        if (!isLuaInvoke(i)) continue;
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ALOAD, 0));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WL, "beforeResume", OBJ));
        m.instructions.insertBefore(i, before);
        InsnList after = new InsnList();
        after.add(new VarInsnNode(Opcodes.ALOAD, 0));
        after.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WL, "afterResume", OBJ));
        m.instructions.insert(i, after);
        m.maxStack += 1; n++;
      }
    }
    ClassNode api = read(a[1]);
    for (MethodNode m : (List<MethodNode>) api.methods) {
      if (!m.name.equals("invoke") || !m.desc.equals("(Lorg/luaj/vm2/Varargs;)Lorg/luaj/vm2/Varargs;")) continue;
      InsnList p = new InsnList();
      p.add(new VarInsnNode(Opcodes.ALOAD, 0));
      p.add(new FieldInsnNode(Opcodes.GETFIELD, A, "this$0", "L" + M + ";"));
      p.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WL, "apiCall", OBJ));
      m.instructions.insert(p); m.maxStack = Math.max(m.maxStack, 1); n++;
    }
    ClassNode ctx = read(a[2]);
    for (MethodNode m : (List<MethodNode>) ctx.methods) {
      if (!m.name.equals("yield")) continue;
      for (AbstractInsnNode i = m.instructions.getFirst(); i != null; i = i.getNext()) {
        if (!isLuaInvoke(i)) continue;
        InsnList p = new InsnList();
        p.add(new VarInsnNode(Opcodes.ALOAD, 0));
        p.add(new FieldInsnNode(Opcodes.GETFIELD, C, "this$1", "L" + A + ";"));
        p.add(new FieldInsnNode(Opcodes.GETFIELD, A, "this$0", "L" + M + ";"));
        p.add(new MethodInsnNode(Opcodes.INVOKESTATIC, WL, "apiCall", OBJ));
        m.instructions.insert(i, p); m.maxStack += 1; n++;
      }
    }
    if (n != 3) throw new RuntimeException("expected 3 patch points, found " + n);
    write(mc, a[3] + "/LuaJLuaMachine.class"); write(api, a[3] + "/LuaJLuaMachine$2.class"); write(ctx, a[3] + "/LuaJLuaMachine$2$1.class");
    System.out.println("LuaJLuaMachine: world lock hooks at resume, API calls and API resumes");
  }
  static boolean isLuaInvoke(AbstractInsnNode i) {
    if (!(i instanceof MethodInsnNode)) return false;
    MethodInsnNode mi = (MethodInsnNode) i;
    return mi.getOpcode() == Opcodes.INVOKEVIRTUAL && mi.owner.equals("org/luaj/vm2/LuaValue") && mi.name.equals("invoke") && mi.desc.equals("(Lorg/luaj/vm2/Varargs;)Lorg/luaj/vm2/Varargs;");
  }
  static ClassNode read(String p) throws IOException { ClassNode c = new ClassNode(); new ClassReader(new FileInputStream(p)).accept(c, 0); return c; }
  static void write(ClassNode c, String p) throws IOException { ClassWriter w = new ClassWriter(0); c.accept(w); new File(p).getParentFile().mkdirs(); FileOutputStream o = new FileOutputStream(p); o.write(w.toByteArray()); o.close(); }
}
