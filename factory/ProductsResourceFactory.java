package org.javiermf.features.services.rest;

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
 * Factory for GenericSSVChk (--factory-src) for ProductsResource. Builds the V1 / V2 resource (class names come from the
 * system properties ssv.v1 / ssv.v2) and replaces its @Autowired ProductsService with a small deterministic fake,
 * IDENTICAL for both versions:
 *   getAllProductNames -> [p1..p5]
 *   findByName         -> a Product (no-arg constructor, field "name" set when present)
 *   add / deleteByName -> throw IllegalArgumentException iff the name is null or blank, otherwise succeed
 * The three sub-resource fields are filled with plain instances when they have a no-arg constructor.
 * Works whether ProductsService is an interface (dynamic proxy) or a class (subclass generated at run time).
 * Needs a JDK (javax.tools) to run.
 */
public class ProductsResourceFactory {

    static final String SERVICE = "org.javiermf.features.services.ProductsService";

    // ---- the fake behaviour (called from the generated subclass / proxy)
    public static List<String> names() { return new ArrayList<String>(Arrays.asList("p1", "p2", "p3", "p4", "p5")); }
    static boolean blank(Object o) { return o == null || o.toString().trim().isEmpty(); }
    static Class<?> productType;
    public static Object dispatch(String method, Object[] a) {
        if (method.equals("getAllProductNames")) return names();
        if (method.equals("findByName")) return product(a[0]);
        if (method.equals("add") || method.equals("deleteByName")) {
            if (blank(a[0])) throw new IllegalArgumentException("invalid product name");
            return null;
        }
        return null;
    }
    static Object product(Object name) {
        try {
            Object p = productType.getDeclaredConstructor().newInstance();
            try { Field f = productType.getDeclaredField("name"); f.setAccessible(true); f.set(p, name == null ? "?" : name.toString()); } catch (Throwable ignored) {}
            return p;
        } catch (Throwable t) { return null; }
    }

    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    private static Object build(String className) throws Exception {
        Class<?> c = Class.forName(className);
        Object resource = c.getDeclaredConstructor().newInstance();
        Field f = c.getDeclaredField("productsService");
        f.setAccessible(true);
        f.set(resource, fakeService(f.getType()));
        for (Field g : c.getDeclaredFields()) {
            if (g.getName().equals(f.getName()) || Modifier.isStatic(g.getModifiers()) || g.getType().isPrimitive()) continue;
            try { g.setAccessible(true); g.set(resource, g.getType().getDeclaredConstructor().newInstance()); } catch (Throwable ignored) {}
        }
        return resource;
    }

    private static Class<?> generated;

    private static Object fakeService(Class<?> svc) throws Exception {
        if (svc.isInterface())
            return Proxy.newProxyInstance(svc.getClassLoader(), new Class<?>[]{svc}, (p, m, a) -> { if (m.getName().equals("findByName")) productType = m.getReturnType(); Object r = dispatch(m.getName(), a); return r == null && m.getReturnType().isPrimitive() ? (m.getReturnType() == boolean.class ? (Object) Boolean.FALSE : (Object) 0) : r; });
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
        String fac = ProductsResourceFactory.class.getCanonicalName();
        for (Method m : svc.getMethods()) {
            String n = m.getName();
            if (!(n.equals("getAllProductNames") || n.equals("findByName") || n.equals("add") || n.equals("deleteByName"))) continue;
            if (n.equals("findByName")) productType = m.getReturnType();
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
