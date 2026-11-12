package com.pfa.pack.controller.api;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Factory for GenericSSVChk (--factory-src) for the Project Tracking System CredentialResource. Builds the V1 / V2 controller
 * (class names from the system properties ssv.v1 / ssv.v2) through its constructor, passing a deterministic fake
 * CredentialService, IDENTICAL for both versions. The fake works from the service's own generic signatures, so it needs no
 * knowledge of the entity classes:
 *   - a collection result (List<X>, or a holder such as DtoCollection<X>) holds two instances of X;
 *   - an entity / DTO result is a bean whose int fields take the ids passed in, whose date field takes the date passed in and whose
 *     text fields record the service method and its arguments (so calling another method, or other ids, gives another output);
 *   - save / update return a copy of the entity passed in, and fail with IllegalArgumentException for null;
 *   - a negative id is rejected with IllegalArgumentException; void methods return nothing.
 * Parameters of the controller are text (ids and a date "dd-MM-yyyyHH:mm:ss") that it parses itself, so the factory also
 *   candidates(String): "1", "2" and two parseable dates, added to the checker's blank / word / long strings, and
 *   base(types): a VALID argument combination ("1", "1", "01-01-202010:00:00" or a built entity), around which the checker
 *   runs a second one-factor-at-a-time sweep (opt-in hook of the checker), so every method is exercised on its success path.
 * Works whether the service is an interface (dynamic proxy) or a class (subclass generated at run time). Needs a JDK.
 */
public class CredentialResourceFactory {

    // ------------------------------------------------------------------ hook: hand-built arguments
    public static List<Object> candidates(Class<?> t) {
        List<Object> r = new ArrayList<Object>();
        try {
            if (t == String.class) { r.add("1"); r.add("2"); r.add("alice"); r.add("bob"); r.add("  alice "); }
            else if (t.getSimpleName().equals("Credential")) { r.add(entity(t, 1)); r.add(entity(t, 2)); }
        } catch (Exception e) { throw new IllegalStateException(e); }
        return r;
    }
    /** a VALID argument combination: a numeric id / user name text, or a built entity */
    public static Object[] base(Class<?>[] types) {
        try {
            Object[] r = new Object[types.length];
            for (int i = 0; i < types.length; i++) {
                Class<?> t = types[i];
                if (t == String.class) r[i] = "1";
                else if (t.getSimpleName().equals("Credential")) r[i] = entity(t, 1);
                else return null;
            }
            return r;
        } catch (Exception e) { return null; }
    }
    /** an entity whose int field takes the id and whose text fields a fixed text */
    static Object entity(Class<?> t, int id) throws Exception {
        Object o = bean(t, 0, false);
        stamp(o, new Object[]{id}, "given", "");
        return o;
    }

    // ------------------------------------------------------------------ the fake services
    public static Method method(Class<?> c, String n, Class<?>[] types) {
        try { return c.getMethod(n, types); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Object dispatch(Method m, Object[] a) {
        try {
            String n = m.getName();
            if (a == null) a = new Object[0];
            for (Object x : a) if (x instanceof Number && ((Number) x).longValue() < 0) throw new IllegalArgumentException("negative id");
            if (n.equals("save") || n.equals("update")) {
                if (a[0] == null) throw new IllegalArgumentException("entity is null");
                Object c = bean(a[0].getClass(), 0, false);
                for (Class<?> k = a[0].getClass(); k != null && k != Object.class; k = k.getSuperclass())
                    for (Field f : k.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true); f.set(c, f.get(a[0]));
                    }
                return c;
            }
            return value(m.getGenericReturnType(), a, n, "");
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static Class<?> raw(Type t) {
        if (t instanceof Class) return (Class<?>) t;
        if (t instanceof ParameterizedType) return raw(((ParameterizedType) t).getRawType());
        return Object.class;
    }
    static Type arg0(Type t) {
        if (t instanceof ParameterizedType) { Type[] x = ((ParameterizedType) t).getActualTypeArguments(); if (x.length > 0) return x[0]; }
        return Object.class;
    }
    static boolean hasCollectionField(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields())
                if (!Modifier.isStatic(f.getModifiers()) && Collection.class.isAssignableFrom(f.getType())) return true;
        return false;
    }
    static Object value(Type t, Object[] a, String n, String tag) throws Exception {
        Class<?> c = raw(t);
        if (c == void.class || c == Void.class) return null;
        if (Collection.class.isAssignableFrom(c) || c == Iterable.class) {
            List<Object> l = new ArrayList<Object>();
            for (int i = 1; i <= 2; i++) l.add(value(arg0(t), a, n, tag + "#" + i));
            return l;
        }
        if (c == String.class) return n + ":" + Arrays.toString(a) + tag;
        if (c == Integer.class || c == int.class) return 1;
        if (c == Long.class || c == long.class) return 1L;
        if (c == Boolean.class || c == boolean.class) return true;
        if (c.getName().startsWith("java.") || c.isInterface()) return null;
        if (t instanceof ParameterizedType && hasCollectionField(c)) {                       // a holder such as DtoCollection<X>
            List<Object> l = new ArrayList<Object>();
            for (int i = 1; i <= 2; i++) l.add(value(arg0(t), a, n, tag + "#" + i));
            for (Constructor<?> k : c.getDeclaredConstructors())
                if (k.getParameterCount() == 1 && k.getParameterTypes()[0].isAssignableFrom(ArrayList.class)) { k.setAccessible(true); return k.newInstance(l); }
            Object h = instantiate(c);
            for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
                for (Field f : k.getDeclaredFields())
                    if (!Modifier.isStatic(f.getModifiers()) && Collection.class.isAssignableFrom(f.getType())) { f.setAccessible(true); f.set(h, l); return h; }
            return h;
        }
        Object o = bean(c, 0, false);
        stamp(o, a, n, tag);
        return o;
    }
    /** int fields take the ids passed in, a date field the date passed in, text fields record the method, its arguments and the tag */
    static void stamp(Object o, Object[] a, String n, String tag) throws Exception {
        List<Integer> ints = new ArrayList<Integer>();
        java.time.LocalDateTime date = null;
        for (Object x : a) {
            if (x instanceof Integer) ints.add((Integer) x);
            else if (x instanceof Long) ints.add(((Long) x).intValue());
            else if (x instanceof java.time.LocalDateTime) date = (java.time.LocalDateTime) x;
        }
        int idx = 0;                                       // 0 for a single result, i-1 for the i-th element of a collection
        int h = tag.lastIndexOf('#');
        if (h >= 0) { try { idx = Integer.parseInt(tag.substring(h + 1)) - 1; } catch (NumberFormatException ignore) { } }
        int k = 0;
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass())
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Class<?> ft = f.getType();
                if (ft == int.class || ft == Integer.class) { f.set(o, (ints.isEmpty() ? 7 : ints.get(k++ % ints.size())) + idx); }
                else if (ft == long.class || ft == Long.class) { f.set(o, (long) ((ints.isEmpty() ? 7 : ints.get(k++ % ints.size())) + idx)); }
                else if (ft == double.class || ft == Double.class) { f.set(o, 1.5 + idx); }
                else if (ft == float.class || ft == Float.class) { f.set(o, 1.5f + idx); }
                else if (ft == BigDecimal.class) { f.set(o, BigDecimal.valueOf(1 + idx)); }
                else if (ft == java.time.LocalDateTime.class) { f.set(o, (date != null ? date : java.time.LocalDateTime.of(2020, 1, 1, 10, 0)).plusMinutes(idx)); }
                else if (ft == String.class) { f.set(o, n + ":" + Arrays.toString(a) + tag); }
            }
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
    private static final List<String> FAKED = Arrays.asList("findAll", "findById", "save", "update", "deleteById", "findByUsername", "deleteByUsername");

    private static Object fakeService(Class<?> svc) throws Exception {
        if (svc.isInterface())
            return Proxy.newProxyInstance(svc.getClassLoader(), new Class<?>[]{svc}, (p, m, a) -> {
                if (m.getName().equals("toString")) return "fake:" + svc.getSimpleName();
                if (m.getName().equals("hashCode")) return 3;
                if (m.getName().equals("equals")) return p == a[0];
                return adapt(dispatch(m, a), m.getReturnType());
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
        String fac = CredentialResourceFactory.class.getCanonicalName();
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
            StringBuilder types = new StringBuilder();
            for (int i = 0; i < ps.length; i++) { if (i > 0) types.append(", "); types.append(ps[i].getCanonicalName()).append(".class"); }
            String call = fac + ".dispatch(" + fac + ".method(" + svc.getCanonicalName() + ".class, \"" + n + "\", new Class<?>[]{" + types + "}), new Object[]{" + args + "})";
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
