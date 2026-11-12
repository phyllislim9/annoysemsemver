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
 * Factory for GenericSSVChk (--factory-src). Builds the V1 / V2 ProductsConfigurationResource (class names come from
 * the system properties ssv.v1 / ssv.v2 that GenericSSVChk sets) and replaces its two @Autowired fields with
 * deterministic fakes, IDENTICAL for both versions:
 *   configurationsService (ProductsConfigurationsService):
 *     getConfigurationsNamesForProduct(p)   -> [c1..c5] for every p (non-empty, so list-shaping changes show)
 *     findByNameAndProductName(p, c)        -> a ProductConfiguration whose "name" is c; throws IllegalArgumentException if p is null
 *     add(p, c) / deleteByName(p, c)        -> throw IllegalArgumentException if c is null or blank, else return normally
 *   productsConfigurationFeaturesResource   -> a plain instance of the sub-resource (no service behind it)
 * The IllegalArgumentExceptions stand in for the real service rejecting bad input, so V1 has inputs on which it
 * crashes (needed to observe crash fixes).
 *
 * Works whether ProductsConfigurationsService is an interface (dynamic proxy) or a class (a subclass is generated and
 * compiled at run time, calling the smallest constructor with null/zero arguments; void methods are supported).
 * Needs a JDK (javax.tools) to run.
 */
public class ProductsConfigurationResourceFactory {

    static final String CONFIG_MODEL = "org.javiermf.features.models.ProductConfiguration";

    // ---- the fake behaviour (called from the generated subclass / proxy)
    public static List<String> names() { return new ArrayList<String>(Arrays.asList("c1", "c2", "c3", "c4", "c5")); }

    static boolean blank(Object s) { return s == null || ((String) s).trim().isEmpty(); }

    static Object newConfiguration(String name) throws Exception {
        Class<?> c = Class.forName(CONFIG_MODEL);
        Constructor<?>[] cs = c.getDeclaredConstructors();
        Arrays.sort(cs, (x, y) -> Integer.compare(x.getParameterCount(), y.getParameterCount()));
        Constructor<?> k = cs[0];
        k.setAccessible(true);
        Class<?>[] pt = k.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = pt[i] == boolean.class ? (Object) false : pt[i].isPrimitive() ? zero(pt[i]) : null;
        Object o = k.newInstance(args);
        try { Field f = c.getDeclaredField("name"); f.setAccessible(true); f.set(o, name); } catch (NoSuchFieldException ignore) { }
        return o;
    }
    static Object zero(Class<?> t) {
        if (t == long.class) return 0L; if (t == int.class) return 0; if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0; if (t == double.class) return 0.0; if (t == float.class) return 0.0f;
        if (t == char.class) return (char) 0; return null;
    }

    public static Object dispatch(String method, Object[] a) {
        try {
            if (method.equals("getConfigurationsNamesForProduct")) return names();
            if (method.equals("findByNameAndProductName")) {
                if (a[0] == null) throw new IllegalArgumentException("productName must not be null");
                return newConfiguration((String) a[1]);
            }
            if (method.equals("add") || method.equals("deleteByName")) {
                if (blank(a[1])) throw new IllegalArgumentException("invalid configuration name");
                return null;
            }
            return null;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    private static Object build(String className) throws Exception {
        Class<?> c = Class.forName(className);
        Object resource = c.getDeclaredConstructor().newInstance();
        Field f = c.getDeclaredField("configurationsService");
        f.setAccessible(true);
        f.set(resource, fakeService(f.getType()));
        try {
            Field g = c.getDeclaredField("productsConfigurationFeaturesResource");
            g.setAccessible(true);
            Constructor<?> k = g.getType().getDeclaredConstructor();
            k.setAccessible(true);
            g.set(resource, k.newInstance());
        } catch (NoSuchFieldException | NoSuchMethodException ignore) { }
        return resource;
    }

    private static Class<?> generated;

    private static Object fakeService(Class<?> svc) throws Exception {
        if (svc.isInterface())
            return Proxy.newProxyInstance(svc.getClassLoader(), new Class<?>[]{svc}, (p, m, a) -> dispatch(m.getName(), a));
        if (generated == null) generated = generateSubclass(svc);
        return generated.getDeclaredConstructor().newInstance();
    }

    private static final List<String> FAKED = Arrays.asList(
            "getConfigurationsNamesForProduct", "findByNameAndProductName", "add", "deleteByName");

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
        String fac = ProductsConfigurationResourceFactory.class.getCanonicalName();
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
            src.append(") { ");
            String call = fac + ".dispatch(\"" + n + "\", new Object[]{" + args + "})";
            if (isVoid) src.append(call).append("; }\n");
            else if (m.getReturnType().isPrimitive()) src.append("Object r = ").append(call).append("; return r == null ? ").append(primitiveZero(m.getReturnType())).append(" : (").append(boxed(m.getReturnType())).append(") r; }\n");
            else src.append("return (").append(m.getReturnType().getCanonicalName()).append(") ").append(call).append("; }\n");
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

    private static String primitiveZero(Class<?> t) { return t == boolean.class ? "false" : t == long.class ? "0L" : "(" + t.getName() + ") 0"; }
    private static String boxed(Class<?> t) {
        if (t == boolean.class) return "Boolean"; if (t == int.class) return "Integer"; if (t == long.class) return "Long";
        if (t == double.class) return "Double"; if (t == float.class) return "Float"; if (t == short.class) return "Short";
        if (t == byte.class) return "Byte"; return "Character";
    }
}
