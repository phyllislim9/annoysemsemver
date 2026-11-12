package com.giassi.microservice.demo2.rest;

import java.io.File;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;


/**
 * Factory for GenericSSVChk (--factory-src) for RBACRestController. Builds the V1 / V2 controller (class names come from
 * the system properties ssv.v1 / ssv.v2) and replaces its @Autowired RoleService with a small deterministic fake,
 * IDENTICAL for both versions:
 *   getRoleList / getPermissionList      -> five Role / Permission objects (r1..r5 / p1..p5)
 *   getRoleById                          -> a Role for every id (null included)
 *   createRole                           -> IllegalArgumentException iff the name is null/blank, else a Role
 *   deleteRole                           -> IllegalArgumentException iff the id is null
 *   add/removePermissionOnRole           -> IllegalArgumentException iff id is null or key is null/blank, else a Role
 * Role / Permission objects are built reflectively (no-arg constructor, String fields = name, Long/Integer fields = id,
 * collection fields = empty). Works whether RoleService is an interface (dynamic proxy) or a class (subclass generated
 * at run time). Needs a JDK (javax.tools) to run.
 */
public class RBACRestControllerFactory {

    static final String SERVICE = "com.giassi.microservice.demo2.rest.users.services.RoleService";

    // ---- the fake behaviour (called from the generated subclass / proxy)
    static final String ENT = "com.giassi.microservice.demo2.rest.users.entities.";
    static boolean blank(Object o) { return o == null || o.toString().trim().isEmpty(); }

    static Object make(String simpleName, String name, long id) {
        try {
            Class<?> c = Class.forName(ENT + simpleName);
            Constructor<?> k = c.getDeclaredConstructor();
            k.setAccessible(true);
            Object o = k.newInstance();
            for (Class<?> t = c; t != null && t != Object.class; t = t.getSuperclass()) {
                for (Field f : t.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    Object v = null;
                    if (ft == String.class) v = name;
                    else if (ft == Long.class || ft == long.class) v = Long.valueOf(id);
                    else if (ft == Integer.class || ft == int.class) v = Integer.valueOf((int) id);
                    else if (java.util.Set.class.isAssignableFrom(ft)) v = new java.util.HashSet<Object>();
                    else if (java.util.List.class.isAssignableFrom(ft) || ft == java.util.Collection.class || ft == Iterable.class) v = new ArrayList<Object>();
                    else if (java.util.Map.class.isAssignableFrom(ft)) v = new java.util.HashMap<Object, Object>();
                    if (v == null) continue;
                    try { f.setAccessible(true); f.set(o, v); } catch (Throwable ignored) {}
                }
            }
            return o;
        } catch (Throwable t) { throw new IllegalStateException("cannot build " + simpleName + ": " + t, t); }
    }
    static List<Object> many(String simple, String prefix) {
        List<Object> l = new ArrayList<Object>();
        for (int i = 1; i <= 5; i++) l.add(make(simple, prefix + i, i));
        return l;
    }
    public static Object dispatch(String method, Object[] a) {
        switch (method) {
            case "getRoleList": return many("Role", "r");
            case "getPermissionList": return many("Permission", "p");
            case "getRoleById": return make("Role", "r" + (a[0] == null ? 1 : a[0]), a[0] instanceof Long ? (Long) a[0] : 1L);
            case "createRole":
                if (blank(a[0])) throw new IllegalArgumentException("invalid role name");
                return make("Role", a[0].toString(), 1L);
            case "deleteRole":
                if (a[0] == null) throw new IllegalArgumentException("invalid role id");
                return null;
            case "addPermissionOnRole":
            case "removePermissionOnRole":
                if (a[0] == null || blank(a[1])) throw new IllegalArgumentException("invalid role id / permission key");
                return make("Role", "r" + a[0], (Long) a[0]);
            default: return null;
        }
    }

    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    private static Object build(String className) throws Exception {
        Class<?> c = Class.forName(className);
        Object resource = c.getDeclaredConstructor().newInstance();
        Field f = c.getDeclaredField("roleService");
        f.setAccessible(true);
        f.set(resource, fakeService(f.getType()));
        return resource;
    }

    private static Class<?> generated;

    private static Object fakeService(Class<?> svc) throws Exception {
        if (svc.isInterface())
            return Proxy.newProxyInstance(svc.getClassLoader(), new Class<?>[]{svc}, (p, m, a) -> { Object r = dispatch(m.getName(), a); return r == null && m.getReturnType().isPrimitive() ? (m.getReturnType() == boolean.class ? (Object) Boolean.FALSE : (Object) 0) : r; });
        if (generated == null) generated = generateSubclass(svc);
        return generated.getDeclaredConstructor().newInstance();
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
        StringBuilder src = new StringBuilder("package " + pkg + ";\npublic class " + name + " extends " + svc.getCanonicalName() + " {\n");
        src.append("  public ").append(name).append("() { super(").append(superArgs).append("); }\n");
        String fac = RBACRestControllerFactory.class.getCanonicalName();
        for (Method m : svc.getMethods()) {
            String n = m.getName();
            if (!java.util.Arrays.asList("getRoleList","getPermissionList","getRoleById","createRole","deleteRole","addPermissionOnRole","removePermissionOnRole").contains(n)) continue;
            if (Modifier.isFinal(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            src.append("  @Override public ").append(m.getReturnType().getCanonicalName()).append(' ').append(n).append('(');
            Class<?>[] ps = m.getParameterTypes();
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) { src.append(", "); args.append(", "); }
                src.append(ps[i].getCanonicalName()).append(" a").append(i);
                args.append("a").append(i);
            }
            String call = fac + ".dispatch(\"" + n + "\", new Object[]{" + args + "})";
            Class<?> rt = m.getReturnType();
            if (rt == void.class) src.append(") { ").append(call).append("; }\n");
            else if (rt.isPrimitive()) src.append(") { ").append(call).append("; return ").append(rt == boolean.class ? "false" : "(" + rt.getName() + ") 0").append("; }\n");
            else src.append(") { return (").append(rt.getCanonicalName()).append(") ").append(call).append("; }\n");
        }
        src.append("}\n");
        File dir = Files.createTempDirectory("ssvfake").toFile();
        File file = new File(dir, name + ".java");
        Files.write(file.toPath(), src.toString().getBytes("UTF-8"));
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IllegalStateException("javax.tools compiler unavailable: run the checker with a JDK, not a JRE");
        int rc = jc.run(null, null, null, "-proc:none", "-nowarn", "-cp", System.getProperty("java.class.path"), "-d", dir.getPath(), file.getPath());
        if (rc != 0) throw new IllegalStateException("could not generate fake subclass of " + svc.getName() + ":\n" + src);
        return new URLClassLoader(new URL[]{dir.toURI().toURL()}, svc.getClassLoader()).loadClass(pkg + "." + name);
    }
}
