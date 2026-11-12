package org.languagetool.server;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.file.Files;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Factory for GenericSSVChk (--factory-src). Builds the V1 / V2 ApiV2 (class names come from the system properties
 * ssv.v1 / ssv.v2 that GenericSSVChk sets) through its (TextChecker, String) constructor, identically for both:
 *   - the TextChecker is a do-nothing instance of TextChecker (a generated concrete subclass, allocated WITHOUT running
 *     any constructor, so no server, thread pool or database is started) whose "config" field is a default HTTPServerConfig
 *   - allowOriginUrl is null
 * ApiV2 and TextChecker are package-private, so this factory lives in org.languagetool.server and the helper subclass is
 * defined into the same class loader and package (MethodHandles.Lookup.defineClass). Needs a JDK (javax.tools) to run.
 * Run the checker with --include-nonpublic: ApiV2 has no public methods.
 */
public class ApiV2Factory {

    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    private static Object build(String className) throws Exception {
        Class<?> c = Class.forName(className);
        Constructor<?>[] cs = c.getDeclaredConstructors();
        Constructor<?> k = null;
        for (Constructor<?> x : cs) if (x.getParameterCount() == 2) k = x;
        if (k == null) throw new IllegalStateException("no (TextChecker, String) constructor in " + className);
        k.setAccessible(true);
        Object checker = fakeTextChecker(k.getParameterTypes()[0]);
        return k.newInstance(checker, null);
    }

    private static Object unsafe() throws Exception {
        Field f = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return f.get(null);
    }

    private static Object allocate(Class<?> c) throws Exception {
        Object u = unsafe();
        return u.getClass().getMethod("allocateInstance", Class.class).invoke(u, c);
    }

    private static Class<?> concreteChecker;

    private static Object fakeTextChecker(Class<?> tc) throws Exception {
        if (concreteChecker == null) concreteChecker = Modifier.isAbstract(tc.getModifiers()) ? generateConcrete(tc) : tc;
        Object o = allocate(concreteChecker);
        Object config = newInstance(Class.forName("org.languagetool.server.HTTPServerConfig"));
        for (Class<?> t = concreteChecker; t != null && t != Object.class; t = t.getSuperclass()) {
            try {
                Field f = t.getDeclaredField("config");
                f.setAccessible(true);
                f.set(o, config);
                break;
            } catch (NoSuchFieldException ignore) { }
        }
        return o;
    }

    private static Object newInstance(Class<?> c) throws Exception {
        Constructor<?> k = smallest(c);
        k.setAccessible(true);
        Class<?>[] pt = k.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = pt[i] == boolean.class ? (Object) false : pt[i].isPrimitive() ? zero(pt[i]) : null;
        return k.newInstance(args);
    }
    private static Object zero(Class<?> t) {
        if (t == long.class) return 0L; if (t == int.class) return 0; if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0; if (t == double.class) return 0.0; if (t == float.class) return 0.0f;
        if (t == char.class) return (char) 0; return null;
    }
    private static Constructor<?> smallest(Class<?> c) {
        Constructor<?>[] cs = c.getDeclaredConstructors();
        Arrays.sort(cs, (x, y) -> Integer.compare(x.getParameterCount(), y.getParameterCount()));
        return cs[0];
    }
    private static String superArgs(Constructor<?> k) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] pt = k.getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pt[i] == boolean.class ? "false" : pt[i].isPrimitive() ? "(" + pt[i].getName() + ") 0" : "(" + pt[i].getCanonicalName() + ") null");
        }
        return sb.toString();
    }
    private static String defaultValue(Class<?> t) {
        if (t == void.class) return null;
        if (t == boolean.class) return "false";
        if (t == long.class) return "0L";
        if (t.isPrimitive()) return "(" + t.getName() + ") 0";
        return "null";
    }

    /** concrete subclass of the abstract TextChecker, defined in the SAME loader and package (it is package-private) */
    private static Class<?> generateConcrete(Class<?> abs) throws Exception {
        String pkg = abs.getPackage().getName();
        String name = "SsvFake" + abs.getSimpleName();
        StringBuilder src = new StringBuilder("package " + pkg + ";\n@SuppressWarnings({\"unchecked\",\"rawtypes\"})\npublic class " + name + " extends " + abs.getCanonicalName() + " {\n");
        src.append("  public ").append(name).append("() { super(").append(superArgs(smallest(abs))).append("); }\n");
        Set<String> done = new HashSet<>();
        for (Class<?> t = abs; t != null && t != Object.class; t = t.getSuperclass()) {
            List<Method> all = new ArrayList<>(Arrays.asList(t.getDeclaredMethods()));
            for (Class<?> i : t.getInterfaces()) all.addAll(Arrays.asList(i.getMethods()));
            for (Method m : all) {
                if (!Modifier.isAbstract(m.getModifiers())) continue;
                StringBuilder sig = new StringBuilder(m.getName() + "(");
                for (Class<?> p : m.getParameterTypes()) sig.append(p.getName()).append(',');
                if (!done.add(sig.toString())) continue;
                src.append("  public ").append(m.getReturnType().getCanonicalName()).append(' ').append(m.getName()).append('(');
                Class<?>[] ps = m.getParameterTypes();
                for (int i = 0; i < ps.length; i++) { if (i > 0) src.append(", "); src.append(ps[i].getCanonicalName()).append(" a").append(i); }
                String dv = defaultValue(m.getReturnType());
                src.append(") ");
                Class<?>[] ex = m.getExceptionTypes();
                if (ex.length > 0) { src.append("throws "); for (int i = 0; i < ex.length; i++) { if (i > 0) src.append(", "); src.append(ex[i].getCanonicalName()); } src.append(' '); }
                src.append("{ ").append(dv == null ? "" : "return " + dv + ";").append(" }\n");
            }
        }
        src.append("}\n");
        File dir = Files.createTempDirectory("ssvfake").toFile();
        File file = new File(dir, name + ".java");
        Files.write(file.toPath(), src.toString().getBytes("UTF-8"));
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IllegalStateException("javax.tools compiler unavailable: run the checker with a JDK, not a JRE");
        int rc = jc.run(null, null, null, "-proc:none", "-nowarn", "-cp", System.getProperty("java.class.path"), "-d", dir.getPath(), file.getPath());
        if (rc != 0) throw new IllegalStateException("could not generate " + name + ":\n" + src);
        byte[] bytes = Files.readAllBytes(new File(dir, pkg.replace('.', File.separatorChar) + File.separator + name + ".class").toPath());
        return MethodHandles.privateLookupIn(abs, MethodHandles.lookup()).defineClass(bytes);
    }
}
