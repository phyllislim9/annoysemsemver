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
 * Factory for GenericSSVChk (--factory-src). Builds the V1 / V2 ProductsFeaturesResource (class names come from the
 * system properties ssv.v1 / ssv.v2 that GenericSSVChk sets) and replaces its @Autowired ProductsService with a small
 * deterministic fake, IDENTICAL for both versions:
 *   getFeaturesForProduct(p)                 -> an insertion-ordered Set of 5 Features f1..f5 (never null, so list-shaping
 *                                               changes show; distinct ids so equals/hashCode cannot merge them)
 *   addFeatureToProduct(p, name, description)-> throws IllegalArgumentException if any argument is null
 *   updateFeatureOfProduct(p, name, desc)    -> a Feature with that name/description; throws IllegalArgumentException if p or name is null
 *   deleteFeatureOfProduct(p, name)          -> throws IllegalArgumentException if p or name is null
 * (Blank strings are accepted on purpose, so version 1 is valid on most generated inputs. The exceptions stand in for
 * the real service rejecting bad input, which gives version 1 inputs on which it crashes.)
 *
 * Feature is instantiated through its smallest constructor (null/zero arguments) and its id/name/description fields are
 * set by reflection; if it is abstract a concrete subclass is generated at run time. Works whether ProductsService is an
 * interface (dynamic proxy) or a class (a subclass is generated and compiled at run time; void methods are supported).
 * Needs a JDK (javax.tools) to run.
 */
public class ProductsFeaturesResourceFactory {

    static final String FEATURE = "org.javiermf.features.models.Feature";

    // ---- the fake behaviour (called from the generated subclass / proxy)
    public static Object dispatch(String method, Object[] a) {
        try {
            if (method.equals("getFeaturesForProduct")) {
                // list-backed Set: never calls Feature.hashCode()/equals() (a JPA entity may hash a null product and throw)
                final java.util.List<Object> items = new java.util.ArrayList<Object>();
                for (int i = 1; i <= 5; i++) items.add(newFeature(i, "f" + i, "d" + i));
                return new java.util.AbstractSet<Object>() {
                    public java.util.Iterator<Object> iterator() { return items.iterator(); }
                    public int size() { return items.size(); }
                    public boolean add(Object o) { return items.add(o); }
                };
            }
            if (method.equals("addFeatureToProduct")) {
                for (Object o : a) if (o == null) throw new IllegalArgumentException("argument must not be null");
                return null;
            }
            if (method.equals("updateFeatureOfProduct")) {
                if (a[0] == null || a[1] == null) throw new IllegalArgumentException("argument must not be null");
                return newFeature(7, (String) a[1], (String) a[2]);
            }
            if (method.equals("deleteFeatureOfProduct")) {
                if (a[0] == null || a[1] == null) throw new IllegalArgumentException("argument must not be null");
                return null;
            }
            return null;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Object newFeature(long id, String name, String description) throws Exception {
        Class<?> c = Class.forName(FEATURE);
        Class<?> k = Modifier.isAbstract(c.getModifiers()) ? generateConcrete(c) : c;
        Object o;
        try { o = instantiate(k); }
        catch (Throwable t) {   // constructor needs real collaborators: skip it
            Field uf = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
            uf.setAccessible(true);
            Object u = uf.get(null);
            o = u.getClass().getMethod("allocateInstance", Class.class).invoke(u, k);
        }
        setField(o, "id", id);
        setField(o, "name", name);
        setField(o, "description", description);
        return o;
    }

    static void setField(Object o, String field, Object value) {
        for (Class<?> t = o.getClass(); t != null && t != Object.class; t = t.getSuperclass()) {
            try {
                Field f = t.getDeclaredField(field);
                f.setAccessible(true);
                Class<?> ft = f.getType();
                if (value instanceof Long) {
                    long v = (Long) value;
                    f.set(o, ft == Long.class || ft == long.class ? (Object) v : ft == Integer.class || ft == int.class ? (Object) (int) v : null);
                } else if (ft == String.class) f.set(o, value);
                return;
            } catch (NoSuchFieldException ignore) { }
            catch (Exception e) { return; }
        }
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
            "getFeaturesForProduct", "addFeatureToProduct", "updateFeatureOfProduct", "deleteFeatureOfProduct");

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
        String fac = ProductsFeaturesResourceFactory.class.getCanonicalName();
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
