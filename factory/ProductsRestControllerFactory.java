package market.rest;

import java.io.File;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Factory for GenericSSVChk (--factory-src). Builds the V1 / V2 ProductsRestController (class names come from the system
 * properties ssv.v1 / ssv.v2 that GenericSSVChk sets) with a deterministic fake ProductService, IDENTICAL for both versions:
 *   findAll()      -> three Products with ids 3, 1, 2 (deliberately unsorted; a List, converted to a Set if the service returns a Set)
 *   findById(id)   -> Optional.of(product with that id) for ids 1..3, Optional.empty() for any other id
 * The real ProductDtoAssembler runs on those fakes. A servlet request context is installed on the thread (and inherited by
 * the checker's worker threads), because the controller's HATEOAS linkTo(methodOn(...)) calls need one.
 * No argument hook is needed: getProducts() takes nothing and getProduct takes a long.
 * Works whether ProductService is an interface (dynamic proxy) or a class (subclass generated at run time, allocated without
 * running a constructor). Needs a JDK (javax.tools) to run.
 */
public class ProductsRestControllerFactory {

    // ------------------------------------------------------------------ hook: hand-built arguments
    public static List<Object> candidates(Class<?> t) {
        return new ArrayList<Object>();
    }

    // ------------------------------------------------------------------ the fake services
    public static Object dispatch(String method, Object[] a) {
        try {
            if (method.equals("findAll")) {
                List<Object> products = new ArrayList<Object>();
                for (long id : new long[]{3, 1, 2}) products.add(product(id));
                return products;
            }
            if (method.equals("findById")) {
                long id = ((Number) a[0]).longValue();
                return id >= 1 && id <= 3 ? Optional.of(product(id)) : Optional.empty();
            }
            return null;
        } catch (RuntimeException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    static Object product(long id) throws Exception {
        Object o = bean(Class.forName("market.domain.Product"), 0, false);
        setField(o, "id", id);
        return o;
    }

    /** a List result is converted to the collection type the service method really declares (Set, Collection, ...) */
    public static Object adapt(Object r, Class<?> target) {
        if (r instanceof List && Set.class.isAssignableFrom(target)) return new LinkedHashSet<Object>((List<?>) r);
        return r;
    }

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

    // ------------------------------------------------------------------ servlet request context
    private static boolean contextDone;
    static synchronized void requestContext() {
        if (contextDone) return;
        contextDone = true;
        try {
            Class<?> req = null;
            for (String n : new String[]{"javax.servlet.http.HttpServletRequest", "jakarta.servlet.http.HttpServletRequest"}) {
                try { req = Class.forName(n); break; } catch (ClassNotFoundException ignore) { }
            }
            if (req == null) throw new ClassNotFoundException("HttpServletRequest");
            Object proxy = Proxy.newProxyInstance(req.getClassLoader(), new Class<?>[]{req}, (p, m, a) -> {
                switch (m.getName()) {
                    case "getScheme": return "http";
                    case "getServerName": return "localhost";
                    case "getServerPort": return 8080;
                    case "getContextPath": case "getServletPath": return "";
                    case "getRequestURI": return "/products";
                    case "getRequestURL": return new StringBuffer("http://localhost:8080/products");
                    case "getMethod": return "GET";
                    case "getProtocol": return "HTTP/1.1";
                    case "getRemoteAddr": return "127.0.0.1";
                    case "getHeaders": case "getHeaderNames": return Collections.emptyEnumeration();
                    case "getParameterMap": return new HashMap<String, String[]>();
                    case "toString": return "stub-request";
                    case "hashCode": return 11;
                    case "equals": return p == a[0];
                }
                Class<?> rt = m.getReturnType();
                return rt == boolean.class ? (Object) false : rt.isPrimitive() ? zero(rt) : null;
            });
            Class<?> attrsC = Class.forName("org.springframework.web.context.request.ServletRequestAttributes");
            Object attrs = attrsC.getConstructor(req).newInstance(proxy);
            Class<?> holder = Class.forName("org.springframework.web.context.request.RequestContextHolder");
            holder.getMethod("setRequestAttributes", Class.forName("org.springframework.web.context.request.RequestAttributes"), boolean.class).invoke(null, attrs, true);
        } catch (Throwable t) {
            System.err.println("[factory] could not install a request context (linkTo(...) will fail): " + t);
        }
    }

    // ------------------------------------------------------------------ building the controller
    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    private static Object build(String className) throws Exception {
        requestContext();
        Class<?> c = Class.forName(className);
        Constructor<?> k = null;
        for (Constructor<?> x : c.getDeclaredConstructors()) if (x.getParameterCount() == 1) k = x;
        if (k == null) throw new IllegalStateException("no (ProductService) constructor in " + className);
        k.setAccessible(true);
        return k.newInstance(fakeService(k.getParameterTypes()[0]));
    }

    private static final Map<Class<?>, Class<?>> generated = new LinkedHashMap<>();
    private static final List<String> FAKED = Arrays.asList("findAll", "findById");

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
        String fac = ProductsRestControllerFactory.class.getCanonicalName();
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
