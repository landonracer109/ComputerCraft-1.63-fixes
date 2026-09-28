import java.io.*; import java.util.*; import java.util.zip.*;
import org.objectweb.asm.*; import org.objectweb.asm.tree.*;
// Compile-time only: makes the given fields public in copies of their classes, like Forge's access
// transformer does when the game runs (TileEntity.worldObj is public in-game).
// args: minecraft.jar outDir class/Name.field [class/Name.field ...]
public class AccessWiden {
  public static void main(String[] a) throws Exception {
    ZipFile jar = new ZipFile(a[0]);
    Map<String, List<String>> want = new LinkedHashMap<String, List<String>>();
    for (int i = 2; i < a.length; i++) {
      int dot = a[i].lastIndexOf('.');
      String c = a[i].substring(0, dot);
      if (!want.containsKey(c)) want.put(c, new ArrayList<String>());
      want.get(c).add(a[i].substring(dot + 1));
    }
    for (Map.Entry<String, List<String>> e : want.entrySet()) {
      ClassNode cn = new ClassNode();
      new ClassReader(jar.getInputStream(jar.getEntry(e.getKey() + ".class"))).accept(cn, 0);
      for (String f : e.getValue()) {
        boolean found = false;
        for (FieldNode fn : (List<FieldNode>) cn.fields) if (fn.name.equals(f)) { fn.access = (fn.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC; found = true; }
        if (!found) throw new RuntimeException("no field " + e.getKey() + "." + f);
      }
      ClassWriter cw = new ClassWriter(0); cn.accept(cw);
      File out = new File(a[1], e.getKey() + ".class"); out.getParentFile().mkdirs();
      FileOutputStream o = new FileOutputStream(out); o.write(cw.toByteArray()); o.close();
    }
  }
}
