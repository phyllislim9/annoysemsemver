package org.javiermf.features.services.rest;

import java.io.File;
import java.lang.reflect.*;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Factory for GenericSSVChk (--factory-src). Builds the V1 / V2 ProductsConstraintsResource (class names come from the
 * system properties ssv.v1 / ssv.v2 that GenericSSVChk sets) and replaces its @Autowired ProductsService with a small
 * deterministic fake, IDENTICAL for both versions:
 *   addRequiresConstraintToProduct(p, source, required) / addExcludesConstraintToProduct(p, source, excluded)
 *        -> a FeatureConstraint with id 7; throws IllegalArgumentException if any argument is null
 *   deleteConstraintFromProduct(p, id)
 *        -> returns normally; throws IllegalArgumentException if p or id is null
 * (Blank strings are accepted on purpose: that keeps version 1 valid on most generated inputs, so changes are
 * observable. The IllegalArgumentExceptions stand in for the real service rejecting bad input, which gives version 1
 * inputs on which it crashes.)
 *
 * FeatureConstraint is probably abstract in the real project: if it is, a concrete subclass is generated and compiled at
 * run time (abstract methods return defaults). Works whether ProductsService is an interface (dynamic proxy) or a class
 * (a subclass is generated and compiled at run time, calling the smallest constructor with null/zero arguments; void
 * methods are supported). Needs a JDK (javax.tools) to run.
 */
public class ProductsConstraintsResourceFactory {

    static final String CONSTRAINT = "org.javiermf.features.models.constraints.FeatureConstraint";

    // ---- the fake behaviour (called from the generated subclass / proxy)
    public static Object dispatch(String method, Object[] a) {
        try {
            if (method.equals("addRequiresConstraintToProduct") || method.equals("addExcludesConstraintToProduct")) {
                for (Object o : a) if (o == null) throw new IllegalArgumentException("argument must not be null");
                return newConstraint(7L);
            }
            if (method.equals("deleteConstraintFromProduct")) {
                if (a[0] == null || a[1] == null) throw new IllegalArgumentException("argument must not be null");
                return null;
            }
            return null;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Object newConstraint(long id) throws Exception {
        Class<?> c = Class.forName(CONSTRAINT);
        Class<?> k = Modifier.isAbstract(c.getModifiers()) ? generateConcrete(c) : c;
        Object o = instantiate(k);
        for (Class<?> t = k; t != null && t != Object.class; t = t.getSuperclass()) {
            try {
                Field f = t.getDeclaredField("id");
                f.setAccessible(true);
                Class<?> ft = f.getType();
                f.set(o, ft == Long.class || ft == long.class ? (Object) id : ft == Integer.class || ft == int.class ? (Object) (int) id : null);
                break;
            } catch (NoSuchFieldException ignore) { }
        }
        return o;
    }

    static Object instantiate(Class<?> c) throws Exception {
        Constructor<?>[] cs = c.getDeclaredConstructors();
        Arrays.sort(cs, (x, y) -> Integer.compare(x.getParameterCount(), y.getParameterCount()));
        Constructor<?> k = cs[0];
        k.setAccessible(true);
        Class<?>[] pt = k.getParameterTypes();
        Object[] args = new Object[pt.length];
        for (int i = 0; i < pt.length; i++) args[i] = pt[i] == boolean.class ? (Object) false : pt[i].isPrimitive() ? zero(pt[i]) : null;
        return k.newInstance(args);
    }
    static Object zero(Class<?> t) {
        if (t == long.class) return 0L; if (t == int.class) return 0; if (t == short.class) return (short) 0;
        if (t == byte.class) return (byte) 0; if (t == double.class) return 0.0; if (t == float.class) return 0.0f;
        if (t == char.class) return (char) 0; return null;
    }

    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    private static Object build(String className) throws Exception {
        Class<?> c = Class.forName(className);
        Object resource = c.getDeclaredConstructor().newInstance();
        Field f = c.getDeclaredField("productsService");
        f.setAccessible(true);
        f.set(resource, fakeService(f.getType()));
        return resource;
    }

    private static Class<?> generatedService;
    private static final Map<Class<?>, Class<?>> concrete = new LinkedHashMap<>();

    private static Object fakeService(Class<?> svc) throws Exception {
        if (svc.isInterface())
            return Proxy.newProxyInstance(svc.getClassLoader(), new Class<?>[]{svc}, (p, m, a) -> dispatch(m.getName(), a));
        if (generatedService == null) generatedService = generateServiceSubclass(svc);
        return generatedService.getDeclaredConstructor().newInstance();
    }

    private static final List<String> FAKED = Arrays.asList(
            "addRequiresConstraintToProduct", "addExcludesConstraintToProduct", "deleteConstraintFromProduct");

    private static String superArgs(Constructor<?> k) {
        StringBuilder sb = new StringBuilder();
        Class<?>[] pt = k.getParameterTypes();
        for (int i = 0; i < pt.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(pt[i] == boolean.class ? "false" : pt[i].isPrimitive() ? "(" + pt[i].getName() + ") 0" : "(" + pt[i].getCanonicalName() + ") null");
        }
        return sb.toString();
    }

    private static Constructor<?> smallest(Class<?> svc) {
        Constructor<?>[] cs = svc.getDeclaredConstructors();
        Arrays.sort(cs, (x, y) -> Integer.compare(x.getParameterCount(), y.getParameterCount()));
        return cs[0];
    }

    private static String defaultValue(Class<?> t) {
        if (t == void.class) return null;
        if (t == boolean.class) return "false";
        if (t == long.class) return "0L";
        if (t.isPrimitive()) return "(" + t.getName() + ") 0";
        return "null";
    }

    private static Class<?> compile(String pkg, String name, StringBuilder src, ClassLoader parent) throws Exception {
        File dir = Files.createTempDirectory("ssvfake").toFile();
        File file = new File(dir, name + ".java");
        Files.write(file.toPath(), src.toString().getBytes("UTF-8"));
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) throw new IllegalStateException("javax.tools compiler unavailable: run the checker with a JDK, not a JRE");
        int rc = jc.run(null, null, null, "-proc:none", "-nowarn", "-cp", System.getProperty("java.class.path"), "-d", dir.getPath(), file.getPath());
        if (rc != 0) throw new IllegalStateException("could not generate " + name + ":\n" + src);
        return new URLClassLoader(new URL[]{dir.toURI().toURL()}, parent).loadClass(pkg + "." + name);
    }

    /** concrete subclass of an abstract model class: every abstract method returns a default value */
    private static Class<?> generateConcrete(Class<?> abs) throws Exception {
        if (concrete.containsKey(abs)) return concrete.get(abs);
        String pkg = abs.getPackage().getName();
        String name = "SsvFake" + abs.getSimpleName();
        StringBuilder src = new StringBuilder("package " + pkg + ";\n@SuppressWarnings({\"unchecked\",\"rawtypes\"})\npublic class " + name + " extends " + abs.getCanonicalName() + " {\n");
        src.append("  public ").append(name).append("() { super(").append(superArgs(smallest(abs))).append("); }\n");
        java.util.Set<String> done = new java.util.HashSet<>();
        for (Class<?> t = abs; t != null && t != Object.class; t = t.getSuperclass()) {
            java.util.List<Method> all = new java.util.ArrayList<>(Arrays.asList(t.getDeclaredMethods()));
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
                src.append(") { ").append(dv == null ? "" : "return " + dv + ";").append(" }\n");
            }
        }
        src.append("}\n");
        Class<?> c = compile(pkg, name, src, abs.getClassLoader());
        concrete.put(abs, c);
        return c;
    }

    private static Class<?> generateServiceSubclass(Class<?> svc) throws Exception {
        String pkg = svc.getPackage().getName();
        String name = "SsvFake" + svc.getSimpleName();
        StringBuilder src = new StringBuilder("package " + pkg + ";\npublic class " + name + " extends " + svc.getCanonicalName() + " {\n");
        src.append("  public ").append(name).append("() { super(").append(superArgs(smallest(svc))).append("); }\n");
        String fac = ProductsConstraintsResourceFactory.class.getCanonicalName();
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
            else if (m.getReturnType().isPrimitive()) src.append("Object r = ").append(call).append("; return r == null ? ").append(defaultValue(m.getReturnType())).append(" : (").append(boxed(m.getReturnType())).append(") r; }\n");
            else src.append("return (").append(m.getReturnType().getCanonicalName()).append(") ").append(call).append("; }\n");
        }
        src.append("}\n");
        return compile(pkg, name, src, svc.getClassLoader());
    }

    private static String boxed(Class<?> t) {
        if (t == boolean.class) return "Boolean"; if (t == int.class) return "Integer"; if (t == long.class) return "Long";
        if (t == double.class) return "Double"; if (t == float.class) return "Float"; if (t == short.class) return "Short";
        if (t == byte.class) return "Byte"; return "Character";
    }
}
