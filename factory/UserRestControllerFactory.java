package com.giassi.microservice.demo2.rest;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Factory for GenericSSVChk (--factory-src) for UserRestController. Builds the V1 / V2 controller (class names come from
 * the system properties ssv.v1 / ssv.v2) and replaces its @Autowired UserService with a small deterministic fake,
 * IDENTICAL for both versions. The fake is an in-memory user store holding three users (ids 3, 1, 2: deliberately unsorted):
 *   getUserPresentationList        -> UserDTOs of the three users
 *   createUser(dto)                -> IllegalArgumentException if dto is null or its email is blank, else a user carrying dto's fields
 *   getUserById(id)                -> a user with that id (IllegalArgumentException if id is null)
 *   updateUser(id, dto)            -> IllegalArgumentException if id or dto is null, else the user with dto's fields
 *   deleteUserById(id)             -> IllegalArgumentException if id is null
 *   addRole / removeRole(id, rid)  -> IllegalArgumentException if either is null, else the user
 * User / DTO objects are built reflectively (no-arg constructor, fields filled with neutral stand-ins).
 * candidates(Class): hook giving hand-built CreateOrUpdateUserDTO arguments (valid email / blank email / all fields null).
 * Works whether UserService is an interface (dynamic proxy) or a class (subclass generated at run time, allocated without
 * running a constructor). Needs a JDK (javax.tools) to run.
 */
public class UserRestControllerFactory {

    static final String BASE = "com.giassi.microservice.demo2.rest.users.";

    // ------------------------------------------------------------------ hook: hand-built arguments
    public static List<Object> candidates(Class<?> t) {
        List<Object> r = new ArrayList<Object>();
        try {
            if (t.getName().equals(BASE + "dtos.requests.CreateOrUpdateUserDTO")) {
                r.add(dto("alice@example.com", "alice"));
                r.add(dto("", "bob"));
                r.add(dto(null, null));
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        return r;
    }
    static Object dto(String email, String other) throws Exception {
        Object o = instantiate(Class.forName(BASE + "dtos.requests.CreateOrUpdateUserDTO"));
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
                f.setAccessible(true);
                f.set(o, f.getName().toLowerCase().contains("mail") ? email : other);
            }
        return o;
    }

    // ------------------------------------------------------------------ the fake service
    static Object field(Object o, String name) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f.get(o); } catch (Exception ignore) { }
        return null;
    }
    static boolean blank(Object o) { return o == null || o.toString().trim().isEmpty(); }
    static void copyFields(Object from, Object to) {
        for (Class<?> k = from.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    Object v = f.get(from);
                    if (v == null) continue;
                    for (Class<?> t = to.getClass(); t != null && t != Object.class; t = t.getSuperclass())
                        try {
                            Field g = t.getDeclaredField(f.getName());
                            if (Modifier.isStatic(g.getModifiers()) || !g.getType().isAssignableFrom(v.getClass())) break;
                            g.setAccessible(true); g.set(to, v); break;
                        } catch (NoSuchFieldException ignore) { }
                } catch (Throwable ignore) { }
            }
    }
    static Object user(long id) throws Exception {
        Object o = instantiate(Class.forName(BASE + "entities.User"));
        setField(o, "id", id);
        setField(o, "email", "user" + id + "@example.com");
        setField(o, "name", "user" + id);
        fill(o, 0, false);
        return o;
    }
    static Object userDto(Object user) throws Exception {
        Class<?> d = Class.forName(BASE + "dtos.UserDTO");
        for (Constructor<?> k : d.getDeclaredConstructors())
            if (k.getParameterCount() == 1 && k.getParameterTypes()[0].isInstance(user)) { k.setAccessible(true); return k.newInstance(user); }
        Object o = instantiate(d);
        copyFields(user, o);
        fill(o, 0, false);
        return o;
    }
    static long idOf(Object id) { return ((Number) id).longValue(); }

    public static Object dispatch(String method, Object[] a) {
        if (a == null) a = new Object[0];
        try {
            switch (method) {
                case "getUserPresentationList": {
                    List<Object> r = new ArrayList<Object>();
                    for (long id : new long[]{3, 1, 2}) r.add(userDto(user(id)));
                    return r;
                }
                case "createUser": {
                    if (a[0] == null) throw new IllegalArgumentException("dto is null");
                    Object email = field(a[0], "email");
                    if (hasField(a[0], "email") && blank(email)) throw new IllegalArgumentException("email is blank");
                    Object u = user(1);
                    copyFields(a[0], u);
                    return u;
                }
                case "getUserById":
                    if (a[0] == null) throw new IllegalArgumentException("id is null");
                    return user(idOf(a[0]));
                case "updateUser": {
                    if (a[0] == null || a[1] == null) throw new IllegalArgumentException("id or dto is null");
                    Object u = user(idOf(a[0]));
                    copyFields(a[1], u);
                    return u;
                }
                case "deleteUserById":
                    if (a[0] == null) throw new IllegalArgumentException("id is null");
                    return null;
                case "addRole": case "removeRole":
                    if (a[0] == null || a[1] == null) throw new IllegalArgumentException("id or roleId is null");
                    return user(idOf(a[0]));
            }
            return null;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    static boolean hasField(Object o, String name) {
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass())
            try { k.getDeclaredField(name); return true; } catch (NoSuchFieldException ignore) { }
        return false;
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
        if (t.getName().equals("org.bson.types.ObjectId")) return t.getConstructor(String.class).newInstance("000000000000000000000000");
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
        if (t == java.time.LocalDateTime.class) return java.time.LocalDateTime.of(2020, 1, 1, 0, 0);
        if (t == java.time.LocalDate.class) return java.time.LocalDate.of(2020, 1, 1);
        if (t == java.time.Instant.class) return java.time.Instant.EPOCH;
        if (t.isEnum()) { Object[] ec = t.getEnumConstants(); return ec.length > 0 ? ec[0] : null; }
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
                if (ft.getName().equals("org.bson.types.ObjectId")) {
                    f.set(o, ft.getConstructor(String.class).newInstance(String.format("%024x", ((Number) value).longValue())));
                    return;
                }
                if (ft == String.class && value instanceof Number) { f.set(o, String.valueOf(value)); return; }
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
        Constructor<?> k = c.getDeclaredConstructor();
        k.setAccessible(true);
        Object resource = k.newInstance();
        Field f = c.getDeclaredField("userService");
        f.setAccessible(true);
        f.set(resource, fakeService(f.getType()));
        return resource;
    }

    private static final Map<Class<?>, Class<?>> generated = new LinkedHashMap<>();
    private static final List<String> FAKED = Arrays.asList("getUserPresentationList", "createUser", "getUserById", "updateUser", "deleteUserById", "addRole", "removeRole");

    private static Object fakeService(Class<?> svc) throws Exception {
        if (svc.isInterface())
            return Proxy.newProxyInstance(svc.getClassLoader(), new Class<?>[]{svc}, (p, m, a) -> {
                if (m.getName().equals("toString")) return "fake:" + svc.getSimpleName();
                if (m.getName().equals("hashCode")) return 3;
                if (m.getName().equals("equals")) return p == a[0];
                return adapt(dispatch(m.getName(), a), m.getReturnType());
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
        String fac = UserRestControllerFactory.class.getCanonicalName();
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
            String call = fac + ".dispatch(\"" + n + "\", new Object[]{" + args + "})";
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
