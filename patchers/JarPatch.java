import java.io.*; import java.util.*; import java.util.zip.*;
// args: in.jar out.jar entry=file [entry=file ...]; replaces existing entries, adds missing ones,
// and drops jar signature files (the patched classes would no longer match them).
public class JarPatch {
  public static void main(String[] a) throws Exception {
    Map<String,String> repl = new LinkedHashMap<String,String>();
    for (int i = 2; i < a.length; i++) { int k = a[i].indexOf('='); repl.put(a[i].substring(0, k), a[i].substring(k + 1)); }
    ZipInputStream in = new ZipInputStream(new FileInputStream(a[0]));
    ZipOutputStream out = new ZipOutputStream(new FileOutputStream(a[1]));
    byte[] buf = new byte[65536]; ZipEntry e; Set<String> done = new HashSet<String>();
    while ((e = in.getNextEntry()) != null) {
      String n = e.getName();
      if (n.startsWith("META-INF/") && (n.endsWith(".SF") || n.endsWith(".RSA") || n.endsWith(".DSA"))) { System.out.println("dropped " + n); continue; }
      InputStream src = in;
      if ("DELETE".equals(repl.get(n))) { done.add(n); System.out.println("deleted " + n); continue; }
      out.putNextEntry(new ZipEntry(n));
      if ("DELETE".equals(repl.get(n))) { done.add(n); System.out.println("deleted " + n); continue; }
      if (repl.containsKey(n)) { src = new FileInputStream(repl.get(n)); done.add(n); System.out.println("replaced " + n); }
      int r; while ((r = src.read(buf)) > 0) out.write(buf, 0, r);
      if (src != in) src.close();
      out.closeEntry();
    }
    for (Map.Entry<String,String> m : repl.entrySet()) {
      if (done.contains(m.getKey())) continue;
      out.putNextEntry(new ZipEntry(m.getKey())); InputStream src = new FileInputStream(m.getValue());
      int r; while ((r = src.read(buf)) > 0) out.write(buf, 0, r); src.close(); out.closeEntry(); System.out.println("added " + m.getKey());
    }
    in.close(); out.close();
  }
}
