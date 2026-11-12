import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * GenericSSVChk — a benchmark-agnostic SemSemVer consistency checker.
 *
 * Unlike FinalComprehensiveTest.java (which hardcodes 12 test methods tied to
 * RBAC's exact method names and witness inputs), this tool knows NOTHING about
 * any specific class. It:
 *
 *   1. Reflects over V1 and V2 to compute a signature diff (Algorithm 1 style):
 *      removed methods, added methods, changed return/parameter types.
 *
 *   2. For every method with an identical signature in both classes, generates
 *      type-directed candidate inputs (boundary values for primitives, null /
 *      empty / sample values for common reference types), invokes the method
 *      on a FRESH instance of V1 and of V2 for each input, and records the
 *      execution trace: VALID / CRASH / HANG (2s timeout).
 *
 *   3. Compares V1 vs V2 traces per input to detect Output Permute, Fault
 *      Introduce, Fault Repair, and Liveness Break (Algorithm 2 style).
 *
 *   4. Aggregates signature-level and trace-level signals into a single
 *      SemSemVer verdict using max-severity priority (Algorithm 3 style):
 *      MAJOR > MINOR > PATCH > CRASH_FIX > REFACTOR.
 *
 * LIMITATIONS (be upfront about these in the paper):
 *   - Requires both classes to have a public or package-private no-arg
 *     constructor (fresh instance per probe, to avoid state leakage across
 *     inputs that mutate internal state).
 *   - Input generation is type-directed but not exhaustive: it does not do
 *     SMT-based precondition solving, and for arbitrary user-defined
 *     reference types it can only try null and a default-constructed
 *     instance. Domain-specific object graphs will be under-tested.
 *   - "Output Permute" vs "Patch" and "Fault Introduce" vs "Major" follow one
 *     reasonable reading of the paper's formalization; the paper's own major
 *     vs. patch predicates overlap (both list Output Permute / Input
 *     Contract), so this is a judgment call, not a re-derivation of an
 *     unambiguous spec. Document this choice explicitly if you cite it.
 *   - Because this is dynamic/black-box testing, both soundness and
 *     completeness are approximate: a REFACTOR verdict means "no difference
 *     found on the sampled inputs," not "no difference exists."
 *
 * USAGE:
 *   javac GenericSSVChk.java
 *   javac -cp . ControllerV1_Complete.java ControllerV2_Complete.java
 *   java -cp . GenericSSVChk com.test.ControllerV1_Complete com.test.ControllerV2_Complete \
 *       [path/to/ControllerV1_Complete.java path/to/ControllerV2_Complete.java ...]
 *
 *   The optional trailing arguments are .java source files to mine for
 *   string/numeric literal constants (e.g. role names checked for
 *   duplicates), which get added to the candidate input pool. This is a
 *   lightweight substitute for the SMT-based precondition solving the paper
 *   describes in Section 5.5 — it catches domain-specific trigger values
 *   that generic boundary-value generation has no way to guess.
 */
public class GenericSSVChk {

    // ---------------------------------------------------------------- config
    static final long HANG_TIMEOUT_MS = 2000;
    static final int  MAX_INPUTS_PER_METHOD = 24;
    static final long RNG_SEED = 42L;

    // Domain-specific "seed" literals mined from source, so string/numeric
    // constants that matter to a benchmark's logic (e.g. a role name checked
    // for duplicates) show up as candidate inputs even though no generic
    // boundary-value rule would guess them. Capped, and comments are
    // stripped first -- an uncapped mine of a real file (license header,
    // javadoc, imports) can pull in dozens of irrelevant literals, which
    // combinatorially explodes generateInputs() for any multi-parameter
    // method and makes the tool APPEAR to hang with no progress output.
    static final int  MAX_SEED_STRINGS = 20;
    static final int  MAX_SEED_LONGS   = 20;
    static final List<String> SEED_STRINGS = new ArrayList<>();
    static final List<Long>   SEED_LONGS   = new ArrayList<>();

    static String stripComments(String text) {
        // remove /* ... */ block comments (including javadoc) and // line comments
        text = text.replaceAll("(?s)/\\*.*?\\*/", " ");
        text = text.replaceAll("//[^\n]*", " ");
        return text;
    }

    static void seedFromSource(String path) {
        try {
            String raw = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)));
            String text = stripComments(raw);
            java.util.regex.Matcher sm = java.util.regex.Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"").matcher(text);
            while (sm.find() && SEED_STRINGS.size() < MAX_SEED_STRINGS) {
                String lit = sm.group(1);
                if (lit.length() <= 64 && !SEED_STRINGS.contains(lit)) SEED_STRINGS.add(lit);
            }
            java.util.regex.Matcher nm = java.util.regex.Pattern.compile("\\b(\\d{1,18})L?\\b").matcher(text);
            while (nm.find() && SEED_LONGS.size() < MAX_SEED_LONGS) {
                try {
                    long v = Long.parseLong(nm.group(1));
                    if (!SEED_LONGS.contains(v)) SEED_LONGS.add(v);
                } catch (NumberFormatException ignore) {}
            }
            System.err.println("  [seed] " + path + " -> " + SEED_STRINGS.size()
                    + " string literal(s), " + SEED_LONGS.size() + " numeric literal(s) mined so far");
        } catch (Exception e) {
            System.err.println("  [seed] could not read " + path + ": " + e.getMessage());
        }
    }

    // ---------------------------------------------------------------- entry
    static InstanceSupplier v1Supplier, v2Supplier;

    /** Opt-in (--include-nonpublic): besides public methods, also analyse the non-private methods (package-private /
     *  protected) declared by the class itself. For classes such as a package-private helper whose API is not public. */
    static boolean INCLUDE_NONPUBLIC = false;
    /** --http-5xx-crash: a JAX-RS Response / Spring HttpEntity whose status is 5xx counts as a CRASH (the service failed and reported it as an error response) */
    static boolean HTTP_5XX_CRASH = false;
    /** --inputs=corpus.json [--input-mode=typed|tecton|both]: where probe inputs come from.
     *  typed  = type-directed generation only (default without --inputs; the original behaviour)
     *  tecton = only the externally generated corpus (plus injected stand-ins)
     *  both   = union of the two (default with --inputs) */
    static String INPUT_MODE = null;
    /** simple class name; the V2 class under test is shown under the V1 name, so an object of its own class (e.g. a singleton accessor's result) is not a difference */
    static String typeLabel(Class<?> c) {
        String v2 = System.getProperty("ssv.v2"), v1 = System.getProperty("ssv.v1");
        if (v1 != null && v2 != null && c.getName().equals(v2)) return v1.substring(v1.lastIndexOf('.') + 1);
        return c.getSimpleName();
    }
    static int httpStatusOf(Object o) {
        if (o == null) return -1;
        for (Class<?> k = o.getClass(); k != null; k = k.getSuperclass()) {
            String n = k.getName();
            try {
                if (n.equals("javax.ws.rs.core.Response") || n.equals("jakarta.ws.rs.core.Response")) return ((Number) k.getMethod("getStatus").invoke(o)).intValue();
                if (n.equals("org.springframework.http.HttpEntity")) {
                    Object st = o.getClass().getMethod("getStatusCode").invoke(o);
                    try { return ((Number) st.getClass().getMethod("value").invoke(st)).intValue(); } catch (Throwable t) { return -1; }
                }
            } catch (Throwable ignored) { }
        }
        return -1;
    }

    static List<Method> methodsOf(Class<?> c) {
        List<Method> out = new ArrayList<>(Arrays.asList(c.getMethods()));
        if (INCLUDE_NONPUBLIC) {
            for (Method m : c.getDeclaredMethods()) {
                if (Modifier.isPrivate(m.getModifiers()) || Modifier.isPublic(m.getModifiers())) continue;
                m.setAccessible(true);
                out.add(m);
            }
        }
        return out;
    }

    static Method findMethod(Class<?> c, String name, Class<?>[] params) throws NoSuchMethodException {
        try { return c.getMethod(name, params); }
        catch (NoSuchMethodException e) {
            if (INCLUDE_NONPUBLIC) {
                try {
                    Method m = c.getDeclaredMethod(name, params);
                    if (!Modifier.isPrivate(m.getModifiers())) { m.setAccessible(true); return m; }
                } catch (NoSuchMethodException ignore) { }
            }
            throw e;
        }
    }

    public static void main(String[] args) throws Exception {
        String factoryClassName = null;
        List<String> rest = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--factory=")) factoryClassName = a.substring("--factory=".length());
            else if (a.startsWith("--equiv=")) setEquiv(a.substring("--equiv=".length()));
            else if (a.equals("--include-nonpublic")) INCLUDE_NONPUBLIC = true;
            else if (a.equals("--http-5xx-crash")) HTTP_5XX_CRASH = true;
            else if (a.startsWith("--inputs=")) CorpusInputs.load(a.substring("--inputs=".length()));
            else if (a.startsWith("--input-mode=")) INPUT_MODE = a.substring("--input-mode=".length());
            else if (a.startsWith("--corpus-key=")) CorpusInputs.keyOverride = a.substring("--corpus-key=".length());
            else rest.add(a);
        }
        if (INPUT_MODE == null) INPUT_MODE = CorpusInputs.loaded() ? "both" : "typed";
        if (!INPUT_MODE.equals("typed") && !CorpusInputs.loaded()) {
            System.err.println("--input-mode=" + INPUT_MODE + " needs --inputs=corpus.json");
            System.exit(2);
        }
        if (rest.size() < 2) {
            System.err.println("Usage: java GenericSSVChk [--factory=FactoryClassName] [--equiv=order,case,ws,num|none] "
                    + "<V1FullyQualifiedClassName> <V2FullyQualifiedClassName> [sourceFileToSeedFrom ...]");
            System.err.println();
            System.err.println("  --factory=FactoryClassName   optional. A class on the classpath with two");
            System.err.println("    public static Object createV1() / createV2() methods, used instead of");
            System.err.println("    a no-arg constructor -- for classes that need dependency injection");
            System.err.println("    (mocked services, etc.) to be constructed at all.");
            System.exit(2);
        }
        Class<?> v1 = Class.forName(rest.get(0));
        Class<?> v2 = Class.forName(rest.get(1));
        CorpusInputs.setController(rest.get(0));
        for (int i = rest.size() - 1; i >= 2; i--) seedFromSource(rest.get(i));   // V2 first: its new literals are the informative ones

        System.setProperty("ssv.v1", rest.get(0));   // lets one reusable factory build whichever V1/V2 pair is under test
        System.setProperty("ssv.v2", rest.get(1));
        if (factoryClassName != null) {
            Class<?> factory = Class.forName(factoryClassName);
            try { FACTORY_CANDIDATES = factory.getMethod("candidates", Class.class); } catch (NoSuchMethodException ignore) { }
            try { FACTORY_BASE = factory.getMethod("base", Class[].class); } catch (NoSuchMethodException ignore) { }
            Method createV1 = factory.getMethod("createV1");
            Method createV2 = factory.getMethod("createV2");
            v1Supplier = () -> createV1.invoke(null);
            v2Supplier = () -> createV2.invoke(null);
            System.err.println("  [factory] using " + factoryClassName + ".createV1()/.createV2()");
        } else {
            v1Supplier = () -> newInstanceNoArg(v1);
            v2Supplier = () -> newInstanceNoArg(v2);
        }

        Report report = check(v1, v2);
        report.print();
        CorpusInputs.summary();
        // exit code doubles as a machine-readable signal for scripting a batch run
        System.exit(report.verdict.equals("REFACTOR") ? 0 : 1);
    }

    // ------------------------------------------------------------- trace io
    enum Status { VALID, CRASH, HANG }

    static final class Trace {
        final Status status;
        final Object output;
        final String exceptionType;
        String detail = "";      // message and the top stack frames of the exception (shown for faults the checker reports)
        private Trace(Status s, Object out, String exType) { status = s; output = out; exceptionType = exType; }
        Trace withDetail(Throwable t) {
            if (t == null) return this;
            StringBuilder sb = new StringBuilder(String.valueOf(t.getMessage()));
            StackTraceElement[] st = t.getStackTrace();
            for (int i = 0; i < Math.min(5, st.length); i++) sb.append("\n              at ").append(st[i]);
            Throwable c = t.getCause();
            if (c != null) sb.append("\n            caused by ").append(c).append(c.getStackTrace().length > 0 ? "\n              at " + c.getStackTrace()[0] : "");
            detail = sb.toString();
            return this;
        }
        static Trace valid(Object o)      { return new Trace(Status.VALID, o, null); }
        static Trace crash(String exType) { return new Trace(Status.CRASH, null, exType); }
        static Trace hang()               { return new Trace(Status.HANG, null, null); }
    }

    /** a method may mutate a bean it is given (e.g. fill in a default); every call gets its own shallow copy, so one probe cannot change the next one's input */
    static Object[] freshArgs(Object[] args) {
        Object[] out = args.clone();
        for (int i = 0; i < out.length; i++) {
            Object a = out[i];
            if (a == null) continue;
            Class<?> c = a.getClass();
            if (c.isPrimitive() || c.isArray() || c.isEnum() || c.isAnonymousClass() || Proxy.isProxyClass(c) || c.getName().startsWith("java.")
                    || a instanceof Collection || a instanceof Map || a instanceof CharSequence || a instanceof Number) continue;
            try {
                Constructor<?> k = c.getDeclaredConstructor();
                k.setAccessible(true);
                Object copy = k.newInstance();
                for (Class<?> t = c; t != null && t != Object.class; t = t.getSuperclass())
                    for (Field f : t.getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        f.setAccessible(true);
                        f.set(copy, f.get(a));
                    }
                out[i] = copy;
            } catch (Throwable ignore) { }
        }
        return out;
    }

    static Trace invokeWithTimeout(Object instance, Method m, Object[] origArgs) {
        final Object[] args = freshArgs(origArgs);
        ExecutorService ex = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r); t.setDaemon(true); return t;
        });
        try {
            Future<Trace> f = ex.submit(() -> {
                try {
                    Object out = m.invoke(instance, args);
                    if (HTTP_5XX_CRASH && httpStatusOf(out) >= 500) return Trace.crash("HTTP " + httpStatusOf(out));
                    return Trace.valid(out);
                } catch (InvocationTargetException e) {
                    Throwable cause = e.getCause();
                    return Trace.crash(cause == null ? e.getClass().getSimpleName() : cause.getClass().getSimpleName()).withDetail(cause == null ? e : cause);
                } catch (Throwable t) {
                    return Trace.crash(t.getClass().getSimpleName()).withDetail(t);
                }
            });
            return f.get(HANG_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException te) {
            return Trace.hang();
        } catch (Exception e) {
            return Trace.crash(e.getClass().getSimpleName());
        } finally {
            ex.shutdownNow();
        }
    }

    interface InstanceSupplier { Object get() throws Exception; }

    /** No-arg ctor if present; otherwise AUTO-CONSTRUCT: pick the public ctor with the fewest params and fill each
     *  with a type-directed stand-in (primitive default, "" for String, a do-nothing dynamic proxy for interfaces,
     *  recursive auto-construction for concrete classes, else null). Same stand-ins go to V1 and V2, so a
     *  differential comparison of the class under test stays meaningful without a hand-written factory. */
    static Object newInstanceNoArg(Class<?> c) throws Exception {
        Object o;
        try {
            Constructor<?> ctor = c.getDeclaredConstructor();
            ctor.setAccessible(true);
            o = ctor.newInstance();
        } catch (NoSuchMethodException e) {
            o = autoConstruct(c, 0);
        }
        injectFields(o, 0);
        return o;
    }

    /** Field injection for Spring/JAX-RS style classes: fill still-null @Autowired/@Inject/@Resource fields with the same
     *  type-directed stand-ins used for constructor arguments (identical for V1 and V2). */
    static void injectFields(Object o, int depth) {
        if (o == null || depth > 3) return;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers()) || f.getType().isPrimitive()) continue;
                boolean inj = false;
                for (java.lang.annotation.Annotation a : f.getAnnotations()) {
                    String n = a.annotationType().getSimpleName();
                    if (n.equals("Autowired") || n.equals("Inject") || n.equals("Resource")) inj = true;
                }
                if (!inj) continue;
                try {
                    f.setAccessible(true);
                    if (f.get(o) == null) f.set(o, stub(f.getType(), depth + 1));
                } catch (Exception ignored) {}
            }
        }
    }

    static Object autoConstruct(Class<?> c, int depth) throws Exception {
        Constructor<?>[] cs = c.getDeclaredConstructors();
        Arrays.sort(cs, Comparator.comparingInt(Constructor::getParameterCount));
        Exception last = null;
        for (Constructor<?> k : cs) {
            try {
                k.setAccessible(true);
                Class<?>[] pt = k.getParameterTypes();
                Object[] av = new Object[pt.length];
                for (int i = 0; i < pt.length; i++) av[i] = stub(pt[i], depth + 1);
                return k.newInstance(av);
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new InstantiationException("no constructor for " + c);
    }

    static Object stub(Class<?> t, int depth) {
        if (!t.isPrimitive() && depth > 4) return null;
        if (t == boolean.class) return false;
        if (t == char.class) return 'a';
        if (t == byte.class) return (byte) 0;
        if (t == short.class) return (short) 0;
        if (t == int.class) return 0;
        if (t == long.class) return 0L;
        if (t == float.class) return 0f;
        if (t == double.class) return 0d;
        if (t == String.class) return "";
        if (t.isArray()) return Array.newInstance(t.getComponentType(), 0);
        if (t == List.class || t == Collection.class || t == Iterable.class) return new ArrayList<>();
        if (t == Set.class) return new HashSet<>();
        if (t == Map.class) return new HashMap<>();
        if (t == Optional.class) return Optional.empty();
        if (t.isInterface())
            return Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[]{t}, (p, m, a) -> {
                if (m.getName().equals("toString")) return "stub:" + t.getSimpleName();
                if (m.getName().equals("hashCode")) return 1;
                if (m.getName().equals("equals")) return p == a[0];
                Class<?> rt = m.getReturnType();
                if (rt == void.class) return null;
                return stub(rt, depth + 1);   // collections -> empty, interfaces -> proxies, concrete classes -> built instances
            });
        if (depth < 4 && !Modifier.isAbstract(t.getModifiers())) {
            try { Object o = autoConstruct(t, depth); injectFields(o, depth); return o; } catch (Throwable ignored) {}
        }
        return null;
    }

    // -------------------------------------------------------- value display
    /** Render an object by value. Objects whose toString() is the identity default (Class@hash), such as
     *  JAX-RS Response objects, would otherwise differ between V1 and V2 for identical behaviour, so they are
     *  described structurally (JAX-RS status/entity when available, otherwise declared fields, depth-limited). */
    static String plain(Object o) { return scrub(describe(o, 0)); }

    static String scrub(String s) { return s.replaceAll("@[0-9a-fA-F]{4,}", "@#"); }

    static String describe(Object o, int depth) {
        if (o == null) return "null";
        Class<?> c = o.getClass();
        if (c.isPrimitive() || o instanceof CharSequence || o instanceof Number || o instanceof Boolean
                || o instanceof Character || o instanceof Enum) {
            if (NORM && o instanceof Number && EQUIV.contains("num")) {
                try { return new java.math.BigDecimal(o.toString()).stripTrailingZeros().toPlainString(); }
                catch (Exception e) { return o.toString(); }
            }
            return leaf(String.valueOf(o));
        }
        if (depth >= 8) return typeLabel(c);
        if (o instanceof Collection) {
            List<String> parts = new ArrayList<>();
            for (Object e : (Collection<?>) o) parts.add(describe(e, depth + 1));
            if (NORM && EQUIV.contains("order")) Collections.sort(parts);
            return "[" + String.join(", ", parts) + "]";
        }
        if (o instanceof Map) {
            List<String> parts = new ArrayList<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) parts.add(describe(e.getKey(), depth + 1) + "=" + describe(e.getValue(), depth + 1));
            if (NORM && EQUIV.contains("order")) Collections.sort(parts);
            return "{" + String.join(", ", parts) + "}";
        }
        if (c.isArray()) {
            List<String> parts = new ArrayList<>();
            int n = Array.getLength(o);
            for (int i = 0; i < n; i++) parts.add(describe(Array.get(o, i), depth + 1));
            if (NORM && EQUIV.contains("order")) Collections.sort(parts);
            return "[" + String.join(", ", parts) + "]";
        }
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {      // Spring ResponseEntity / HttpEntity: status + body
            if (k.getName().equals("org.springframework.http.HttpEntity")) {
                try {
                    Object body = k.getMethod("getBody").invoke(o), st = null;
                    try { st = c.getMethod("getStatusCode").invoke(o); } catch (Throwable ignored) {}
                    Object loc = null;                                   // Location header, shown only when present
                    try {
                        Object h = k.getMethod("getHeaders").invoke(o);
                        loc = h.getClass().getMethod("getLocation").invoke(h);
                    } catch (Throwable ignored) {}
                    return "Entity{status=" + st + ", body=" + describe(body, depth + 1)
                            + (loc != null ? ", location=" + leaf(String.valueOf(loc)) : "") + "}";
                } catch (Throwable ignored) {}
            }
            if (k.getName().equals("javax.ws.rs.core.Response") || k.getName().equals("jakarta.ws.rs.core.Response")) {
                try {
                    Object st = k.getMethod("getStatus").invoke(o), en = k.getMethod("getEntity").invoke(o);
                    Object loc = null;                                   // Location header (created/redirect responses), shown only when present
                    try { loc = k.getMethod("getLocation").invoke(o); } catch (Throwable ignored) {}
                    return "Response{status=" + st + ", entity=" + describe(en, depth + 1)
                            + (loc != null ? ", location=" + leaf(String.valueOf(loc)) : "") + "}";
                } catch (Throwable ignored) {}
            }
        }
        if (c.getName().startsWith("java.")) return leaf(String.valueOf(o));
        try {
            // a project class with its own toString() is shown by that text, unless it holds a collection, map or
            // array: the text would freeze the element order, so such a class is described by its fields instead,
            // letting the declared order equivalence see through it.
            if (c.getMethod("toString").getDeclaringClass() != Object.class && !holdsCollection(c)) return leaf(String.valueOf(o));
        } catch (Exception ignored) {}
        StringBuilder sb = new StringBuilder(typeLabel(c)).append("{");
        boolean first = true;
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
                try {
                    f.setAccessible(true);
                    if (!first) sb.append(", ");
                    sb.append(f.getName()).append("=").append(describe(f.get(o), depth + 1));
                    first = false;
                } catch (Throwable ignored) {}
            }
        }
        return sb.append("}").toString();
    }

    static boolean holdsCollection(Class<?> c) {
        for (Class<?> k = c; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (Collection.class.isAssignableFrom(t) || Map.class.isAssignableFrom(t) || t.isArray()) return true;
            }
        return false;
    }

    static String canon(Object o) {
        if (o == null) return "null";
        if (o instanceof Object[]) return Arrays.deepToString((Object[]) o);
        if (o.getClass().isArray()) {
            int n = Array.getLength(o);
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < n; i++) { if (i > 0) sb.append(", "); sb.append(Array.get(o, i)); }
            return sb.append("]").toString();
        }
        return plain(o);
    }

    static boolean sameValue(Object a, Object b) { return canon(a).equals(canon(b)); }

    // ------------------------------------------------ declared output equivalence
    // Patch = Output Permute *modulo a declared equivalence*. A value change that stays inside the
    // equivalence is PATCH; a value change that leaves it is MAJOR. Tokens (comma separated):
    //   order : element order of lists/arrays/sets/maps is irrelevant
    //   case  : letter case is irrelevant
    //   ws    : runs of whitespace / leading+trailing whitespace are irrelevant
    //   num   : numeric representation is irrelevant (1 == 1.0 == 1.00)
    // "none" = no equivalence: every observable value change is MAJOR.
    static final Set<String> EQUIV = new TreeSet<>(Arrays.asList("order", "case", "ws", "num"));
    static void setEquiv(String spec) {
        EQUIV.clear();
        for (String t : spec.split(",")) { t = t.trim().toLowerCase(); if (!t.isEmpty() && !t.equals("none")) EQUIV.add(t); }
    }

    static boolean NORM = false;      // true while rendering for equivalence comparison
    static String leaf(String x) {
        if (NORM) {
            if (EQUIV.contains("ws"))   x = x.trim().replaceAll("\\s+", " ");
            if (EQUIV.contains("case")) x = x.toLowerCase();
        }
        return x;
    }
    static String norm(Object o) {
        NORM = true;
        try { return scrub(describe(o, 0)); } finally { NORM = false; }
    }

    /** true iff the two values are different but inside the declared equivalence (a PATCH-level change). */
    static boolean equivalentChange(Object a, Object b) { return norm(a).equals(norm(b)); }

    static int sizeOf(Object o) {
        if (o == null) return -1;
        if (o instanceof Collection) return ((Collection<?>) o).size();
        if (o instanceof Object[]) return ((Object[]) o).length;
        if (o.getClass().isArray()) return Array.getLength(o);
        return -1;
    }

    static String argsToString(Object[] args) {
        Object[] shown = new Object[args.length];
        for (int i = 0; i < args.length; i++) shown[i] = canon(args[i]);
        return Arrays.toString(shown);
    }

    // ---------------------------------------------------- input generation
    /** Optional factory hook: public static List<Object> candidates(Class<?> parameterType). Lets a factory offer
     *  valid, hand-built argument objects for parameter types the checker cannot build itself (an interface such as
     *  java.security.Principal, or a DTO whose fields must hold particular values). Absent hook: no change. */
    static Method FACTORY_CANDIDATES;
    /** optional factory hook Object[] base(Class[] parameterTypes): a VALID combination of arguments for a method with those parameter types (e.g. parseable ids and a date), or null. When present, a second one-factor-at-a-time sweep is run around that base, so methods whose parameters must be valid together are exercised on their success path. */
    static Method FACTORY_BASE;
    static Object[] factoryBase(Class<?>[] types) {
        if (FACTORY_BASE == null) return null;
        try { Object[] r = (Object[]) FACTORY_BASE.invoke(null, (Object) types); return r != null && r.length == types.length ? r : null; } catch (Throwable e) { return null; }
    }
    static List<Object> factoryCandidates(Class<?> t) {
        if (FACTORY_CANDIDATES == null) return Collections.emptyList();
        try {
            Object r = FACTORY_CANDIDATES.invoke(null, t);
            return r == null ? Collections.<Object>emptyList() : (List<Object>) r;
        } catch (Throwable e) { return Collections.emptyList(); }
    }

    static List<Object> candidatesFor(Class<?> t) {
        List<Object> c = new ArrayList<>();
        if (t == long.class || t == Long.class) {
            c.addAll(Arrays.asList(0L, 1L, -1L, Long.MAX_VALUE, Long.MIN_VALUE));
            c.addAll(SEED_LONGS);
            if (!t.isPrimitive()) c.add(null);
        } else if (t == int.class || t == Integer.class) {
            c.addAll(Arrays.asList(0, 1, -1, Integer.MAX_VALUE, Integer.MIN_VALUE));
            for (long v : SEED_LONGS) if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) c.add((int) v);
            if (!t.isPrimitive()) c.add(null);
        } else if (t == short.class || t == Short.class) {
            c.addAll(Arrays.asList((short) 0, (short) 1, (short) -1));
            if (!t.isPrimitive()) c.add(null);
        } else if (t == byte.class || t == Byte.class) {
            c.addAll(Arrays.asList((byte) 0, (byte) 1, (byte) -1));
            if (!t.isPrimitive()) c.add(null);
        } else if (t == double.class || t == Double.class) {
            c.addAll(Arrays.asList(0.0, 1.5, -1.0, Double.NaN));
            if (!t.isPrimitive()) c.add(null);
        } else if (t == float.class || t == Float.class) {
            c.addAll(Arrays.asList(0.0f, 1.5f, -1.0f));
            if (!t.isPrimitive()) c.add(null);
        } else if (t == boolean.class || t == Boolean.class) {
            c.addAll(Arrays.asList(true, false));
            if (!t.isPrimitive()) c.add(null);
        } else if (t == char.class || t == Character.class) {
            c.addAll(Arrays.asList('a', ' ', '0'));
            if (!t.isPrimitive()) c.add(null);
        } else if (t == String.class) {
            c.addAll(Arrays.asList("", " ", "test", "x".repeat(200)));
            c.addAll(SEED_STRINGS);
            c.add(null);
        } else if (t.isEnum()) {
            c.addAll(Arrays.asList(t.getEnumConstants()));
            c.add(null);
        } else if (t.isArray()) {
            c.add(null);
            c.add(Array.newInstance(t.getComponentType(), 0));
        } else if (List.class.isAssignableFrom(t)) {
            c.add(null);
            c.add(new ArrayList<>());
        } else if (Map.class.isAssignableFrom(t)) {
            c.add(null);
            c.add(new HashMap<>());
        } else if (Set.class.isAssignableFrom(t)) {
            c.add(null);
            c.add(new HashSet<>());
        } else {
            // unknown reference type: best effort — null plus a default-constructed instance
            c.add(null);
            try {
                Constructor<?> ctor = t.getDeclaredConstructor();
                ctor.setAccessible(true);
                c.add(ctor.newInstance());
            } catch (Exception ignore) {
                // no no-arg constructor available; null is the only candidate we can offer
            }
        }
        EXTRAS_FROM.put(t, c.size());
        for (Object extra : factoryCandidates(t)) c.add(extra);
        return c;
    }

    static final int MAX_CANDIDATES_PER_PARAM = 16;
    /** index in candidatesFor(t) where the factory's hand-built candidates start; those are never sampled out by the per-parameter cap */
    static final Map<Class<?>, Integer> EXTRAS_FROM = new HashMap<>();

    static List<Object[]> generateInputs(Class<?>[] paramTypes) {
        if (paramTypes.length == 0) return Collections.singletonList(new Object[0]);
        List<List<Object>> perParam = new ArrayList<>();
        for (Class<?> t : paramTypes) {
            List<Object> cands = candidatesFor(t);
            if (cands.size() > MAX_CANDIDATES_PER_PARAM) {
                // cap PER PARAMETER before the cartesian product, not just the
                // final combo list -- capping only after building every
                // combination still means building e.g. 40 x 40 x 40 objects
                // first for a 3-parameter method with heavily-seeded types.
                // always keep null (if offered) and the first 4 boundary values; sample the rest (mined literals etc.)
                List<Object> head = new ArrayList<>(cands.subList(0, 4));
                boolean hasNull = cands.contains(null);
                if (hasNull && !head.contains(null)) head.add(null);
                int extrasFrom = EXTRAS_FROM.getOrDefault(t, cands.size());
                for (int i = Math.max(4, extrasFrom); i < cands.size() && head.size() < MAX_CANDIDATES_PER_PARAM; i++)
                    if (cands.get(i) != null && !head.contains(cands.get(i))) head.add(cands.get(i));      // factory candidates first
                List<Object> tail = new ArrayList<>();
                for (int i = 4; i < Math.min(cands.size(), Math.max(4, extrasFrom)); i++) if (cands.get(i) != null) tail.add(cands.get(i));
                Collections.shuffle(tail, new Random(RNG_SEED));
                head.addAll(tail.subList(0, Math.min(tail.size(), MAX_CANDIDATES_PER_PARAM - head.size())));
                cands = head;
            }
            perParam.add(cands);
        }
        List<Object[]> all = new ArrayList<>();
        cartesian(perParam, 0, new Object[paramTypes.length], all);
        if (all.size() <= MAX_INPUTS_PER_METHOD) return all;
        // One-factor-at-a-time first: a base combo, then every candidate of every parameter varied alone, so each
        // individual value (null, "", a mined literal, ...) is exercised at least once. Random combos fill the rest.
        LinkedHashMap<String, Object[]> chosen = new LinkedHashMap<>();
        Object[] base = new Object[paramTypes.length];
        for (int i = 0; i < base.length; i++) base[i] = perParam.get(i).get(0);
        chosen.put(Arrays.deepToString(base), base);
        for (int i = 0; i < base.length; i++)
            for (Object v : perParam.get(i)) {
                Object[] c = base.clone(); c[i] = v;
                chosen.putIfAbsent(Arrays.deepToString(c), c);
            }
        Object[] vb = factoryBase(paramTypes);
        if (vb != null) {                    // second sweep around the factory's VALID base
            chosen.putIfAbsent(Arrays.deepToString(vb), vb);
            for (int i = 0; i < vb.length; i++)
                for (Object v : perParam.get(i)) { Object[] c = vb.clone(); c[i] = v; chosen.putIfAbsent(Arrays.deepToString(c), c); }
        }
        Collections.shuffle(all, new Random(RNG_SEED));
        for (Object[] c : all) { if (chosen.size() >= Math.max(MAX_INPUTS_PER_METHOD, base.length * MAX_CANDIDATES_PER_PARAM)) break; chosen.putIfAbsent(Arrays.deepToString(c), c); }
        return new ArrayList<>(chosen.values());
    }

    static void cartesian(List<List<Object>> lists, int idx, Object[] current, List<Object[]> out) {
        if (idx == lists.size()) { out.add(current.clone()); return; }
        for (Object v : lists.get(idx)) {
            current[idx] = v;
            cartesian(lists, idx + 1, current, out);
        }
    }

    // --------------------------------------------------------- reflection
    static Set<String> methodNames(Class<?> c) {
        Set<String> s = new TreeSet<>();
        for (Method m : methodsOf(c))
            if (m.getDeclaringClass() != Object.class && !m.isSynthetic() && !m.isBridge())
                s.add(m.getName());
        return s;
    }

    static final class SignatureDiff {
        final Set<String> removedMethods = new TreeSet<>();
        final Set<String> addedMethods = new TreeSet<>();
        final List<String> changedSignatures = new ArrayList<>();
        final List<Method> commonMethods = new ArrayList<>(); // v1 methods with identical name+params+return in v2

        static SignatureDiff compute(Class<?> v1, Class<?> v2) {
            SignatureDiff d = new SignatureDiff();
            Set<String> v1Names = methodNames(v1);
            Set<String> v2Names = methodNames(v2);
            d.removedMethods.addAll(v1Names); d.removedMethods.removeAll(v2Names);
            d.addedMethods.addAll(v2Names);   d.addedMethods.removeAll(v1Names);

            for (Method m1 : methodsOf(v1)) {
                if (m1.getDeclaringClass() == Object.class || m1.isSynthetic() || m1.isBridge()) continue;
                try {
                    Method m2 = findMethod(v2, m1.getName(), m1.getParameterTypes());
                    // a method returning its own class (e.g. a singleton accessor) returns the V2 class in V2: same type
                    String r1 = m1.getGenericReturnType().getTypeName(), r2 = m2.getGenericReturnType().getTypeName().replace(v2.getName(), v1.getName());
                    if (!r1.equals(r2)) {
                        d.changedSignatures.add(m1.getName() + ": return " + m1.getGenericReturnType()
                                + " -> " + m2.getGenericReturnType());
                    } else {
                        d.commonMethods.add(m1);
                    }
                } catch (NoSuchMethodException e) {
                    if (v2Names.contains(m1.getName())) {
                        d.changedSignatures.add(m1.getName() + ": parameter types changed");
                    }
                    // else: already accounted for in removedMethods
                }
            }
            return d;
        }
    }

    // ------------------------------------------------------- trace probing
    static final class MethodReport {
        final String name;
        int inputsTested = 0;
        boolean outputPermute = false, faultIntroduce = false, faultRepair = false,
                livenessBreak = false, outputWeakened = false, nonEquivChange = false;
        final List<String> examples = new ArrayList<>();
        // tracked to detect "collapses to a constant" degradation across many inputs,
        // which isn't visible from any single input/output pair alone
        private final List<Object> v1ValidOutputs = new ArrayList<>();
        private final List<Object> v2ValidOutputs = new ArrayList<>();

        int v1Valid = 0, v1Crash = 0, v1Hang = 0;
        final Set<String> v1Exceptions = new TreeSet<>();

        MethodReport(String n) { name = n; }

        void observe(Object[] args, Trace t1, Trace t2) {
            inputsTested++;
            if (t1.status == Status.VALID) v1Valid++;
            else if (t1.status == Status.HANG) v1Hang++;
            else { v1Crash++; if (t1.exceptionType != null) v1Exceptions.add(t1.exceptionType); }
            String a = (CorpusInputs.isFromCorpus(args) ? "{tecton}" : "") + argsToString(args);
            if (t1.status == Status.VALID) v1ValidOutputs.add(t1.output);
            if (t2.status == Status.VALID) v2ValidOutputs.add(t2.output);

            if (t1.status == Status.VALID && t2.status == Status.VALID) {
                if (!sameValue(t1.output, t2.output)) {
                    outputPermute = true;
                    boolean eq = equivalentChange(t1.output, t2.output);
                    if (!eq) nonEquivChange = true;
                    examples.add(name + a + " :: OUTPUT_PERMUTE" + (eq ? "(equivalent)" : "(NON-EQUIVALENT)") + "  " + canon(t1.output) + "  ->  " + canon(t2.output));
                    int n1 = sizeOf(t1.output), n2 = sizeOf(t2.output);
                    if (n1 > 0 && n2 >= 0 && n2 < n1) {
                        outputWeakened = true;
                        examples.add(name + a + " :: OUTPUT_WEAKENED  size " + n1 + " -> " + n2);
                    }
                }
            } else if (t1.status == Status.VALID && t2.status == Status.CRASH) {
                faultIntroduce = true;
                examples.add(name + a + " :: FAULT_INTRODUCE  V1 valid  ->  V2 throws " + t2.exceptionType + (t2.detail.isEmpty() ? "" : "\n            " + t2.detail));
            } else if (t1.status == Status.CRASH && t2.status == Status.VALID) {
                faultRepair = true;
                examples.add(name + a + " :: FAULT_REPAIR  V1 threw " + t1.exceptionType + "  ->  V2 valid");
            } else if (t1.status == Status.VALID && t2.status == Status.HANG) {
                livenessBreak = true;
                examples.add(name + a + " :: LIVENESS_BREAK  V1 terminates  ->  V2 hangs");
            }
            // CRASH->CRASH, HANG->HANG: no signal recorded (conservative — can't tell if it's the "same" failure)
        }

        /** call after all inputs are observed: catches degradation to a constant
         *  placeholder output that no single-input comparison would reveal. */
        void finalizeConstantCollapse() {
            if (v1ValidOutputs.size() < 2 || v2ValidOutputs.size() < 2) return;
            Set<String> v1Distinct = new HashSet<>();
            for (Object o : v1ValidOutputs) v1Distinct.add(canon(o));
            Set<String> v2Distinct = new HashSet<>();
            for (Object o : v2ValidOutputs) v2Distinct.add(canon(o));
            if (v1Distinct.size() > 1 && v2Distinct.size() == 1) {
                outputWeakened = true;
                examples.add(name + " :: OUTPUT_WEAKENED  collapsed to a constant value ("
                        + v2Distinct.iterator().next() + ") across " + v2ValidOutputs.size()
                        + " distinct inputs, where V1 varied (" + v1Distinct.size() + " distinct outputs)");
            }
        }
    }

    static List<Object[]> selectInputs(Method m) {
        if (INPUT_MODE == null || INPUT_MODE.equals("typed")) return generateInputs(m.getParameterTypes());
        List<Object[]> tecton = CorpusInputs.inputsFor(m);
        if (INPUT_MODE.equals("tecton")) return tecton;
        LinkedHashMap<String, Object[]> all = new LinkedHashMap<>();
        // Tecton inputs first, then the type-directed ones; an input present in both keeps its Tecton identity.
        for (Object[] a : tecton) all.putIfAbsent(dedupKey(a), a);
        for (Object[] a : generateInputs(m.getParameterTypes())) all.putIfAbsent(dedupKey(a), a);
        return new ArrayList<>(all.values());
    }

    /** Key for removing duplicate inputs. Arrays.deepToString calls the entity's own toString(), which throws on objects with
     *  null fields (e.g. an entity built from a Tecton value that leaves a related entity unset), so the structural rendering
     *  used for evidence is used instead, and an object that cannot be rendered at all is kept as distinct. */
    static String dedupKey(Object[] a) {
        try { return argsToString(a); }
        catch (Throwable t) { return "#unrenderable@" + System.identityHashCode(a); }
    }

    static boolean constructWarned = false;
    static MethodReport probeMethod(Class<?> v1c, Class<?> v2c, Method m1) throws Exception {
        Method m2 = findMethod(v2c, m1.getName(), m1.getParameterTypes());
        MethodReport mr = new MethodReport(m1.getName());
        List<Object[]> inputs = selectInputs(m1);
        System.err.println("  [probe] " + m1.getName() + "(" + inputs.size() + " inputs)...");
        for (Object[] args : inputs) {
            Object inst1, inst2;
            try { inst1 = v1Supplier.get(); inst2 = v2Supplier.get(); }
            catch (Exception e) {   // can't construct — skip this probe, but SAY WHY once
                if (!constructWarned) {
                    constructWarned = true;
                    Throwable c = e; while (c.getCause() != null) c = c.getCause();
                    System.err.println("  [ERROR] cannot construct the class under test: " + c);
                    for (StackTraceElement el : c.getStackTrace()) { System.err.println("      at " + el); if (el.getClassName().startsWith("java.base") ) break; }
                }
                continue;
            }
            Trace t1 = invokeWithTimeout(inst1, m1, args);
            Trace t2 = invokeWithTimeout(inst2, m2, args);
            mr.observe(args, t1, t2);
        }
        mr.finalizeConstantCollapse();
        return mr;
    }

    // ---------------------------------------------------------- top level
    static Report check(Class<?> v1, Class<?> v2) throws Exception {
        Report report = new Report(v1.getName(), v2.getName());
        report.signatureDiff = SignatureDiff.compute(v1, v2);
        for (Method m1 : report.signatureDiff.commonMethods) {
            report.methodReports.add(probeMethod(v1, v2, m1));
        }
        report.finalizeVerdict();
        return report;
    }

    static final class Report {
        final String v1Name, v2Name;
        SignatureDiff signatureDiff;
        final List<MethodReport> methodReports = new ArrayList<>();
        String verdict;
        final List<String> reasons = new ArrayList<>();

        Report(String a, String b) { v1Name = a; v2Name = b; }

        void finalizeVerdict() {
            boolean removed    = !signatureDiff.removedMethods.isEmpty();
            boolean added      = !signatureDiff.addedMethods.isEmpty();
            boolean sigChanged = !signatureDiff.changedSignatures.isEmpty();

            boolean faultIntroduce = false, outputPermute = false, faultRepair = false,
                    livenessBreak = false, outputWeakened = false, nonEquiv = false;
            for (MethodReport mr : methodReports) {
                nonEquiv       |= mr.nonEquivChange;
                faultIntroduce |= mr.faultIntroduce;
                outputPermute  |= mr.outputPermute;
                faultRepair    |= mr.faultRepair;
                livenessBreak  |= mr.livenessBreak;
                outputWeakened |= mr.outputWeakened;
            }

            // Max-severity aggregation, priority MAJOR > MINOR > PATCH > CRASH_FIX > REFACTOR
            if (removed || sigChanged || faultIntroduce || livenessBreak || outputWeakened || nonEquiv) {
                verdict = "MAJOR";
                if (removed)    reasons.add("method(s) removed: " + signatureDiff.removedMethods);
                if (sigChanged) reasons.add("signature changed: " + signatureDiff.changedSignatures);
                if (faultIntroduce) reasons.add("fault introduced: a previously-valid input now crashes");
                if (livenessBreak)  reasons.add("liveness broken: a previously-terminating input now hangs");
                if (outputWeakened) reasons.add("output weakened: result shrank or collapsed to a constant on shared input(s)");
                if (nonEquiv)   reasons.add("output changed outside the declared equivalence " + EQUIV + " on shared input(s)");
            } else if (added) {
                verdict = "MINOR";
                reasons.add("method(s) added, nothing removed or changed: " + signatureDiff.addedMethods);
            } else if (outputPermute) {
                verdict = "PATCH";
                reasons.add("output changed only within the declared equivalence " + EQUIV + " (PATCH)");
            } else if (faultRepair) {
                verdict = "CRASH_FIX";
                reasons.add("previously-crashing input now returns a valid result");
            } else {
                verdict = "REFACTOR";
                reasons.add("no observable behavioral difference found across sampled inputs");
            }
        }

        void print() {
            System.out.println("=".repeat(72));
            System.out.println("GENERIC SSVCHK REPORT: " + v1Name + "  ->  " + v2Name);
            System.out.println("=".repeat(72));

            System.out.println("\n[Signature diff]");
            System.out.println("  removed methods: " + signatureDiff.removedMethods);
            System.out.println("  added methods:   " + signatureDiff.addedMethods);
            System.out.println("  changed signatures: " + signatureDiff.changedSignatures);

            System.out.println("\n[Trace diff] (" + methodReports.size() + " shared-signature methods probed)");
            for (MethodReport mr : methodReports) {
                System.out.printf("  %-24s inputs=%-3d permute=%-5b introduce=%-5b repair=%-5b hang=%-5b weaken=%-5b%n",
                        mr.name, mr.inputsTested, mr.outputPermute, mr.faultIntroduce, mr.faultRepair,
                        mr.livenessBreak, mr.outputWeakened);
                System.out.printf("        V1 baseline: valid=%d crash=%d hang=%d %s%n", mr.v1Valid, mr.v1Crash, mr.v1Hang,
                        mr.v1Exceptions.isEmpty() ? "" : "exceptions=" + mr.v1Exceptions);
                if (mr.inputsTested > 0 && mr.v1Valid == 0)
                    System.out.println("        [note] V1 never returns normally on the sampled inputs: output changes on this method are not observable (only crash/liveness changes are)");
                for (String ex : mr.examples) System.out.println("        - " + ex);
            }
            List<String> blindNames = new ArrayList<>(), seenNames = new ArrayList<>();
            for (MethodReport mr : methodReports) {
                if (mr.inputsTested > 0 && mr.v1Valid == 0) blindNames.add(mr.name); else seenNames.add(mr.name);
            }
            System.out.println("[Observability] observed=" + seenNames + " blind=" + blindNames);

            System.out.println("\n[Verdict] " + verdict);
            for (String r : reasons) System.out.println("  - " + r);
            System.out.println("=".repeat(72));
        }
    }
}