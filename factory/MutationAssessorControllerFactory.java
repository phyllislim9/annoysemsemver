package org.cbioportal.genome_nexus.web;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Factory for GenericSSVChk (--factory-src) for the Genome Nexus MutationAssessorController. Builds the V1 / V2 controller
 * (class names from the system properties ssv.v1 / ssv.v2) through its constructor, passing a deterministic fake
 * MutationAssessorService, IDENTICAL for both versions: getMutationAssessor(variant) returns one MutationAssessor for that
 * variant, getMutationAssessor(list) one per element, in order; IllegalArgumentException if the variant / list is null or an
 * element is blank. candidates(List): hand-built variant lists, because the checker alone only offers null and the empty
 * list. Works whether the service is an interface (dynamic proxy) or a class (subclass generated at run time). Needs a JDK.
 */
public class MutationAssessorControllerFactory {

    // ------------------------------------------------------------------ hook: hand-built arguments
    public static List<Object> candidates(Class<?> t) {
        List<Object> r = new ArrayList<Object>();
        if (t == List.class) {
            r.add(new ArrayList<Object>(Arrays.asList("7:g.140453136A>T")));
            r.add(new ArrayList<Object>(Arrays.asList("7:g.140453136A>T", "12:g.25398285C>A")));
            r.add(new ArrayList<Object>(Arrays.asList("7:g.140453136A>T", " ")));
        }
        return r;
    }

    // ------------------------------------------------------------------ the fake services
    static boolean blank(Object o) { return o == null || o.toString().trim().isEmpty(); }
    static Object field(Object o, String name) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (Exception ignore) { }
        return null;
    }
    static Object assessor(String q) throws Exception {
        Object o = bean(Class.forName("org.cbioportal.genome_nexus.model.MutationAssessor"), 0, false);
        setField(o, "variant", q); setField(o, "functionalImpact", "impact of " + q); setField(o, "uniprotId", "U" + q.length());
        return o;
    }
    @SuppressWarnings("unchecked")
    public static Object dispatch(String svc, String method, Object[] a, Class<?> rt) {
        try {
            if (!method.equals("getMutationAssessor")) return null;
            if (List.class.isAssignableFrom(rt)) {
                if (a[0] == null) throw new IllegalArgumentException("list is null");
                List<Object> r = new ArrayList<Object>();
                for (Object e : (List<Object>) a[0]) {
                    if (blank(e)) throw new IllegalArgumentException("element is null or blank");
                    r.add(assessor(e.toString()));
                }
                return r;
            }
            if (blank(a[0])) throw new IllegalArgumentException("variant is null or blank");
            return assessor(a[0].toString());
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static Object adapt(Object r, Class<?> target) { return r; }

    // ------------------------------------------------------------------ bean helpers
    static Object instantiate(Class<?> c) throws Exception {
        Constructor<?>[] cs = c.getDeclaredConstructors();
        Arrays.sort(cs, (x, y) -> Integer.compare(x.getParameterCount(), y.getParameterCount()));
        Exception last = null;
        for (Constructor<?> k : cs) {
            try {
                k.setAccessible(true);
                Class<?>[] pt = k.getParameterTypes();
                Object[] args = new Object[pt.length];
                for (int i = 0; i < pt.length; i++) args[i] = pt[i] == boolean.class ? (Object) false : pt[i].isPrimitive() ? zero(pt[i]) : null;
                return k.newInstance(args);
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new InstantiationException(c.getName());
    }
    static Object zero(Class<?> t) {
        if (t == long.class) return 0L; if (t == int.class) return 0; if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0; if (t == double.class) return 0.0; if (t == float.class) return 0.0f;
        if (t == char.class) return (char) 0; return null;
    }

    /** instance of c with every still-null field given a neutral stand-in, so real assemblers do not hit a null */
    static Object bean(Class<?> c, int depth, boolean props) throws Exception {
        Object o = instantiate(c);
        fill(o, depth, props);
        return o;
    }
    static void fill(Object o, int depth, boolean props) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(o) != null) continue;
                    Object v = standIn(f.getType(), depth, props);
                    if (v != null) f.set(o, v);
                } catch (Throwable ignore) { }
            }
        }
    }
    static Object standIn(Class<?> t, int depth, boolean props) throws Exception {
        if (t == String.class) return "";
        if (t == Long.class) return 1L;
        if (t == Integer.class) return 1;
        if (t == Short.class) return (short) 1;
        if (t == Double.class) return 1.0;
        if (t == Boolean.class) return false;
        if (t == BigDecimal.class) return props ? BigDecimal.TEN : BigDecimal.ZERO;
        if (t == java.util.Date.class) return new java.util.Date(0);
        if (t == List.class || t == Collection.class || t == Iterable.class) return new ArrayList<Object>();
        if (t == Set.class) return new HashSet<Object>();
        if (t == Map.class) return new HashMap<Object, Object>();
        if (t == Optional.class) return Optional.empty();
        if (t.isInterface() || t.isEnum() || t.isArray() || Modifier.isAbstract(t.getModifiers()) || t.getName().startsWith("java.")) return null;
        if (depth >= 2) return null;
        return bean(t, depth + 1, props);
    }
    static void setField(Object o, String field, Object value) {
        for (Class<?> t = o.getClass(); t != null && t != Object.class; t = t.getSuperclass()) {
            try {
                Field f = t.getDeclaredField(field);
                f.setAccessible(true);
                Class<?> ft = f.getType();
                if (value instanceof Number && !ft.isAssignableFrom(value.getClass())) {
                    long v = ((Number) value).longValue();
                    f.set(o, ft == long.class || ft == Long.class ? (Object) v : ft == int.class || ft == Integer.class ? (Object) (int) v : ft == BigDecimal.class ? (Object) BigDecimal.valueOf(v) : null);
                } else f.set(o, value);
                return;
            } catch (NoSuchFieldException ignore) { }
            catch (Exception e) { return; }
        }
    }

    // ------------------------------------------------------------------ building the controller
    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    private static Object build(String className) throws Exception {
        Class<?> c = Class.forName(className);
        Constructor<?> k = null;
        for (Constructor<?> x : c.getDeclaredConstructors()) if (k == null || x.getParameterCount() > k.getParameterCount()) k = x;
        if (k == null || k.getParameterCount() == 0) throw new IllegalStateException("no service constructor in " + className);
        k.setAccessible(true);
        Class<?>[] pt = k.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = fakeService(pt[i]);
        return k.newInstance(args);
    }

    private static final Map<Class<?>, Class<?>> generated = new LinkedHashMap<>();
    private static final List<String> FAKED = Arrays.asList("getMutationAssessor");

    private static Object fakeService(Class<?> svc) throws Exception {
        if (svc.isInterface())
            return Proxy.newProxyInstance(svc.getClassLoader(), new Class<?>[]{svc}, (p, m, a) -> {
                if (m.getName().equals("toString")) return "fake:" + svc.getSimpleName();
                if (m.getName().equals("hashCode")) return 3;
                if (m.getName().equals("equals")) return p == a[0];
                return adapt(dispatch(svc.getSimpleName(), m.getName(), a, m.getReturnType()), m.getReturnType());
            });
        Class<?> g = generated.get(svc);
        if (g == null) { g = generateSubclass(svc); generated.put(svc, g); }
        Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
        uf.setAccessible(true);
        Object u = uf.get(null);
        return u.getClass().getMethod("allocateInstance", Class.class).invoke(u, g);
    }

    private static String defaultValue(Class<?> t) {
        if (t == void.class) return null;
        if (t == boolean.class) return "false";
        if (t == long.class) return "0L";
        if (t.isPrimitive()) return "(" + t.getName() + ") 0";
        return "null";
    }
    private static String boxed(Class<?> t) {
        if (t == boolean.class) return "Boolean"; if (t == int.class) return "Integer"; if (t == long.class) return "Long";
        if (t == double.class) return "Double"; if (t == float.class) return "Float"; if (t == short.class) return "Short";
        if (t == byte.class) return "Byte"; return "Character";
    }

    private static Class<?> generateSubclass(Class<?> svc) throws Exception {
        Constructor<?>[] cs = svc.getDeclaredConstructors();
        Arrays.sort(cs, (x, y) -> Integer.compare(x.getParameterCount(), y.getParameterCount()));
        StringBuilder superArgs = new StringBuilder();
        Class<?>[] pt = cs[0].getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) superArgs.append(", ");
            superArgs.append(pt[i] == boolean.class ? "false" : pt[i].isPrimitive() ? "(" + pt[i].getName() + ") 0" : "(" + pt[i].getCanonicalName() + ") null");
        }
        String pkg = svc.getPackage().getName();
        String name = "SsvFake" + svc.getSimpleName();
        StringBuilder src = new StringBuilder("package " + pkg + ";\n@SuppressWarnings({\"unchecked\",\"rawtypes\"})\npublic class " + name + " extends " + svc.getCanonicalName() + " {\n");
        src.append("  public ").append(name).append("() { super(").append(superArgs).append("); }\n");
        String fac = MutationAssessorControllerFactory.class.getCanonicalName();
        for (Method m : svc.getMethods()) {
            String n = m.getName();
            if (!FAKED.contains(n)) continue;
            if (Modifier.isFinal(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            boolean isVoid = m.getReturnType() == void.class;
            src.append("  @Override public ").append(m.getReturnType().getCanonicalName()).append(' ').append(n).append('(');
            Class<?>[] ps = m.getParameterTypes();
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) { src.append(", "); args.append(", "); }
                src.append(ps[i].getCanonicalName()).append(" a").append(i);
                args.append("a").append(i);
            }
            src.append(") ");
            Class<?>[] ex = m.getExceptionTypes();
            if (ex.length > 0) { src.append("throws "); for (int i = 0; i < ex.length; i++) { if (i > 0) src.append(", "); src.append(ex[i].getCanonicalName()); } src.append(' '); }
            src.append("{ ");
            String call = fac + ".dispatch(\"" + svc.getSimpleName() + "\", \"" + n + "\", new Object[]{" + args + "}, " + m.getReturnType().getCanonicalName() + ".class)";
            if (isVoid) src.append(call).append("; }\n");
            else if (m.getReturnType().isPrimitive()) src.append("Object r = ").append(call).append("; return r == null ? ").append(defaultValue(m.getReturnType())).append(" : (").append(boxed(m.getReturnType())).append(") r; }\n");
            else src.append("return (").append(m.getReturnType().getCanonicalName()).append(") ").append(fac).append(".adapt(").append(call).append(", ").append(m.getReturnType().getCanonicalName()).append(".class); }\n");
        }
        src.append("}\n");
        File dir = Files.createTempDirectory("ssvfake").toFile();
        File file = new File(dir, name + ".java");
        Files.write(file.toPath(), src.toString().getBytes("UTF-8"));
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IllegalStateException("javax.tools compiler unavailable: run the checker with a JDK, not a JRE");
        int rc = jc.run(null, null, null, "-proc:none", "-nowarn", "-cp", System.getProperty("java.class.path"), "-d", dir.getPath(), file.getPath());
        if (rc != 0) throw new IllegalStateException("could not generate fake subclass of " + svc.getName() + ":\n" + src);
        byte[] bytes = Files.readAllBytes(new File(dir, pkg.replace('.', File.separatorChar) + File.separator + name + ".class").toPath());
        return MethodHandles.privateLookupIn(svc, MethodHandles.lookup()).defineClass(bytes);
    }
}
