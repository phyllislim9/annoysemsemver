import java.lang.reflect.*;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/**
 * CorpusInputs -- feeds externally generated inputs (Tecton / tgen, converted by
 * tgen_to_corpus.py) to GenericSSVChk.
 *
 * corpus.json:  { "<Controller>": { "<method>/<arity>": [ [arg1, arg2, ...], ... ] } }
 *   - arity counts only the parameters the spec describes; framework-injected
 *     parameters (Principal, HttpServletRequest, ...) are not in the corpus and are
 *     filled with the same stand-ins the checker already uses (factory candidate,
 *     else a stub).
 *   - a parameter that is KindedDTO<T> arrives as {"kind": "T", "value": {...}}.
 *   - Tecton writes an empty Option as {}; where the Java target is a plain value
 *     (String, number, boolean, enum, ...) that becomes null.
 *
 * No JSON library is needed: a small parser and a reflective binder live here, so
 * the checker still compiles with a bare javac.  An input that cannot be converted
 * to a method's parameter types is skipped and counted, never guessed.
 */
final class CorpusInputs {
    private CorpusInputs() { }

    static final Set<String> INJECTED = new HashSet<>(Arrays.asList(
            "Principal", "Authentication", "HttpServletRequest", "HttpServletResponse",
            "HttpSession", "BindingResult", "Model", "Locale", "WebRequest",
            "UriComponentsBuilder"));      // keep in sync with INJECTED in tgen_plan.py

    static Map<String, Object> CORPUS = null;
    static String controller = null;
    static String keyOverride = null;
    private static final Map<Object[], Boolean> FROM = new IdentityHashMap<>();
    static int usedMethods = 0, skippedMethods = 0, skippedInputs = 0, totalInputs = 0;

    static boolean loaded() { return CORPUS != null; }
    static boolean isFromCorpus(Object[] a) { return FROM.containsKey(a); }

    @SuppressWarnings("unchecked")
    static void load(String path) {
        try {
            Object o = MiniJson.parse(new String(Files.readAllBytes(Paths.get(path)), "UTF-8"));
            CORPUS = (Map<String, Object>) o;
            System.err.println("  [corpus] loaded " + path + " (" + CORPUS.size() + " controller(s))");
        } catch (Exception e) {
            System.err.println("  [corpus] cannot read " + path + ": " + e);
            System.exit(2);
        }
    }

    static void setController(String v1ClassName) {
        controller = keyOverride != null ? keyOverride
                : v1ClassName.substring(v1ClassName.lastIndexOf('.') + 1);
        if (CORPUS != null && !CORPUS.containsKey(controller))
            System.err.println("  [corpus] WARNING: no entry for controller '" + controller
                    + "' (use --corpus-key=NAME to override); known: " + CORPUS.keySet());
    }

    static void summary() {
        if (CORPUS == null) return;
        System.err.println("  [corpus] methods with Tecton inputs: " + usedMethods + ", without: " + skippedMethods
                + "; inputs used: " + (totalInputs - skippedInputs) + " of " + totalInputs
                + " (" + skippedInputs + " not convertible to the Java parameter types)");
    }

    // ------------------------------------------------------------ inputs for a method
    static Object injected(Class<?> t) {
        for (Object c : GenericSSVChk.factoryCandidates(t)) if (c != null) return c;
        return GenericSSVChk.stub(t, 1);
    }

    @SuppressWarnings("unchecked")
    static List<Object[]> inputsFor(Method m) {
        Class<?>[] pt = m.getParameterTypes();
        Type[] gt = m.getGenericParameterTypes();
        List<Integer> real = new ArrayList<>();
        for (int i = 0; i < pt.length; i++) if (!INJECTED.contains(pt[i].getSimpleName())) real.add(i);
        List<Object[]> out = new ArrayList<>();

        if (real.isEmpty()) {            // nothing for Tecton to supply: one call, injected stand-ins only
            Object[] full = new Object[pt.length];
            for (int i = 0; i < pt.length; i++) full[i] = injected(pt[i]);
            out.add(full);
            return out;
        }
        String key = m.getName() + "/" + real.size();
        Object ctl = CORPUS == null ? null : CORPUS.get(controller);
        Object lst = ctl instanceof Map ? ((Map<String, Object>) ctl).get(key) : null;
        if (!(lst instanceof List)) {
            skippedMethods++;
            System.err.println("  [corpus] " + m.getName() + ": no Tecton inputs for " + key);
            return out;
        }
        usedMethods++;
        int skipped = 0, n = 0;
        Throwable firstErr = null;
        for (Object argl : (List<Object>) lst) {
            n++;
            try {
                List<Object> a = (List<Object>) argl;
                Object[] full = new Object[pt.length];
                for (int i = 0; i < pt.length; i++) {
                    int pos = real.indexOf(i);
                    full[i] = pos < 0 ? injected(pt[i]) : bind(a.get(pos), gt[i], new HashMap<TypeVariable<?>, Type>());
                }
                FROM.put(full, Boolean.TRUE);
                out.add(full);
            } catch (Throwable e) {
                skipped++;
                if (firstErr == null) firstErr = e;
            }
        }
        totalInputs += n;
        skippedInputs += skipped;
        System.err.println("  [corpus] " + m.getName() + ": " + (n - skipped) + " Tecton input(s)"
                + (skipped > 0 ? ", " + skipped + " not convertible" : ""));
        if (firstErr != null) {
            Throwable r = firstErr;
            while (r instanceof InvocationTargetException && r.getCause() != null) r = r.getCause();
            System.err.println("  [corpus]   first conversion error: " + r
                    + "   (parameter types: " + Arrays.toString(m.getGenericParameterTypes()) + ")");
        }
        return out;
    }

    // ------------------------------------------------------------------ binder
    static Type resolve(Type t, Map<TypeVariable<?>, Type> env) {
        while (t instanceof TypeVariable && env.containsKey(t)) t = env.get(t);
        return t;
    }

    static Class<?> rawOf(Type t) {
        if (t instanceof Class) return (Class<?>) t;
        if (t instanceof ParameterizedType) return (Class<?>) ((ParameterizedType) t).getRawType();
        if (t instanceof GenericArrayType)
            return Array.newInstance(rawOf(((GenericArrayType) t).getGenericComponentType()), 0).getClass();
        if (t instanceof WildcardType) return rawOf(((WildcardType) t).getUpperBounds()[0]);
        if (t instanceof TypeVariable) return rawOf(((TypeVariable<?>) t).getBounds()[0]);
        return Object.class;
    }

    static Type[] argsOf(Type t) {
        return t instanceof ParameterizedType ? ((ParameterizedType) t).getActualTypeArguments() : new Type[0];
    }

    static boolean isLeaf(Class<?> c) {
        return c.isPrimitive() || c == String.class || c == CharSequence.class || Number.class.isAssignableFrom(c)
                || c == Boolean.class || c == Character.class || c.isEnum();
    }

    static String text(Object j) {
        if (j instanceof String) return (String) j;
        if (j instanceof BigDecimal) return ((BigDecimal) j).toPlainString();
        if (j instanceof Boolean) return j.toString();
        throw new IllegalArgumentException("expected a scalar, got " + j);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object bind(Object j, Type type, Map<TypeVariable<?>, Type> env) throws Exception {
        Type t = resolve(type, env);
        Class<?> raw = rawOf(t);
        Type[] targs = argsOf(t);

        if (raw == Optional.class) {
            if (j == null || (j instanceof Map && ((Map<?, ?>) j).isEmpty())) return Optional.empty();
            return Optional.ofNullable(bind(j, targs.length > 0 ? targs[0] : Object.class, env));
        }
        if (j == null) {
            if (raw.isPrimitive()) throw new IllegalArgumentException("null for primitive " + raw);
            return null;
        }
        if (j instanceof Map && ((Map<?, ?>) j).isEmpty() && (isLeaf(raw) || Collection.class.isAssignableFrom(raw) || raw.isArray()))
            return raw.isPrimitive() ? defaultOf(raw) : null;        // Tecton's "none"

        if (raw == Object.class) return j;
        if (raw == String.class || raw == CharSequence.class) return text(j);
        if (raw == boolean.class || raw == Boolean.class) {
            String s = text(j).toLowerCase();
            if (s.equals("true")) return Boolean.TRUE;
            if (s.equals("false")) return Boolean.FALSE;
            throw new IllegalArgumentException("not a boolean: " + s);
        }
        if (raw == char.class || raw == Character.class) { String s = text(j); if (s.isEmpty()) throw new IllegalArgumentException("empty char"); return s.charAt(0); }
        if (raw == long.class || raw == Long.class) return new BigDecimal(text(j)).longValueExact();
        if (raw == int.class || raw == Integer.class) return new BigDecimal(text(j)).intValueExact();
        if (raw == short.class || raw == Short.class) return new BigDecimal(text(j)).shortValueExact();
        if (raw == byte.class || raw == Byte.class) return new BigDecimal(text(j)).byteValueExact();
        if (raw == double.class || raw == Double.class) return new BigDecimal(text(j)).doubleValue();
        if (raw == float.class || raw == Float.class) return new BigDecimal(text(j)).floatValue();
        if (raw == BigInteger.class) return new BigDecimal(text(j)).toBigIntegerExact();
        if (raw == BigDecimal.class) return new BigDecimal(text(j));
        if (raw == Number.class) return new BigDecimal(text(j));
        if (raw.isEnum()) {
            String s = text(j);
            for (Object c : raw.getEnumConstants()) if (((Enum<?>) c).name().equalsIgnoreCase(s)) return c;
            try { return raw.getEnumConstants()[new BigDecimal(s).intValueExact()]; } catch (Exception e) { throw new IllegalArgumentException("no enum constant " + s + " in " + raw); }
        }
        if (raw.isArray()) {
            if (!(j instanceof List)) throw new IllegalArgumentException("expected array");
            List<Object> l = (List<Object>) j;
            Object arr = Array.newInstance(raw.getComponentType(), l.size());
            Type ct = t instanceof GenericArrayType ? ((GenericArrayType) t).getGenericComponentType() : raw.getComponentType();
            for (int i = 0; i < l.size(); i++) Array.set(arr, i, bind(l.get(i), ct, env));
            return arr;
        }
        if (Collection.class.isAssignableFrom(raw) || raw == Iterable.class) {
            if (!(j instanceof List)) throw new IllegalArgumentException("expected array");
            Type et = targs.length > 0 ? targs[0] : Object.class;
            Collection<Object> out = Set.class.isAssignableFrom(raw) ? new LinkedHashSet<>() : new ArrayList<>();
            for (Object e : (List<Object>) j) out.add(bind(e, et, env));
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            if (!(j instanceof Map)) throw new IllegalArgumentException("expected object");
            Type vt = targs.length > 1 ? targs[1] : Object.class;
            Map<Object, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : ((Map<String, Object>) j).entrySet()) out.put(e.getKey(), bind(e.getValue(), vt, env));
            return out;
        }
        // a string given for a class: try the usual string factories (UUID, LocalDate, URI, ...)
        if (!(j instanceof Map)) {
            String s = text(j);
            for (String f : new String[]{"valueOf", "parse", "fromString"}) {
                try { Method fm = raw.getMethod(f, String.class); if (Modifier.isStatic(fm.getModifiers())) return fm.invoke(null, s); } catch (NoSuchMethodException ignore) { }
                try { Method fm = raw.getMethod(f, CharSequence.class); if (Modifier.isStatic(fm.getModifiers())) return fm.invoke(null, s); } catch (NoSuchMethodException ignore) { }
            }
            try { Constructor<?> c = raw.getConstructor(String.class); return c.newInstance(s); } catch (NoSuchMethodException ignore) { }
            throw new IllegalArgumentException("cannot build " + raw + " from a string");
        }
        // a bean: no-arg constructor, fields set by name; unknown keys ignored, missing keys keep defaults
        if (raw.isInterface() || Modifier.isAbstract(raw.getModifiers())) throw new IllegalArgumentException("cannot instantiate " + raw);
        Map<TypeVariable<?>, Type> env2 = new HashMap<>(env);
        TypeVariable<?>[] tv = raw.getTypeParameters();
        for (int i = 0; i < tv.length && i < targs.length; i++) env2.put(tv[i], resolve(targs[i], env));
        Map<String, Object> m = (Map<String, Object>) j;
        Constructor<?> noarg = null;
        try { noarg = raw.getDeclaredConstructor(); } catch (NoSuchMethodException ignore) { }
        if (noarg == null) return viaConstructor(raw, m, env2);        // e.g. KindedDTO(String kind, T value)
        noarg.setAccessible(true);
        Object bean = noarg.newInstance();
        for (Class<?> k = raw; k != null && k != Object.class; k = k.getSuperclass())
            for (Field f : k.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !m.containsKey(f.getName())) continue;
                f.setAccessible(true);
                f.set(bean, bind(m.get(f.getName()), f.getGenericType(), env2));
            }
        return bean;
    }

    /** Build a bean that has no no-arg constructor: pick a constructor whose parameter names can be found and fill it
     *  from the JSON object (a missing key becomes null / the primitive default). Names come from, in order: real
     *  parameter names (javac -parameters, as Spring Boot builds use), @JsonProperty / @ConstructorProperties, or, if the
     *  constructor takes exactly the class's own fields in declaration order, the field names. */
    static Object viaConstructor(Class<?> raw, Map<String, Object> m, Map<TypeVariable<?>, Type> env) throws Exception {
        Constructor<?>[] cs = raw.getDeclaredConstructors();
        Arrays.sort(cs, (a, b) -> b.getParameterCount() - a.getParameterCount());
        Exception last = null;
        for (Constructor<?> c : cs) {
            try {
                String[] names = ctorParamNames(c, raw);
                if (names == null) continue;
                Class<?>[] pcs = c.getParameterTypes();
                Type[] gts = c.getGenericParameterTypes();
                if (gts.length != pcs.length) gts = pcs;
                Object[] args = new Object[names.length];
                for (int i = 0; i < names.length; i++) {
                    if (!m.containsKey(names[i])) args[i] = pcs[i].isPrimitive() ? defaultOf(pcs[i]) : null;
                    else args[i] = bind(m.get(names[i]), gts[i], env);
                }
                c.setAccessible(true);
                return c.newInstance(args);
            } catch (Exception e) { last = e; }
        }
        throw last != null ? last
                : new NoSuchMethodException("no usable constructor for " + raw + " (no no-arg constructor and no parameter names found)");
    }

    static String[] ctorParamNames(Constructor<?> c, Class<?> raw) {
        Parameter[] ps = c.getParameters();
        String[] names = new String[ps.length];
        boolean all = true;
        for (int i = 0; i < ps.length; i++) {
            String n = ps[i].isNamePresent() ? ps[i].getName() : null;
            for (java.lang.annotation.Annotation a : ps[i].getAnnotations())
                if (a.annotationType().getSimpleName().equals("JsonProperty")) {
                    try { String v = (String) a.annotationType().getMethod("value").invoke(a); if (!v.isEmpty()) n = v; } catch (Exception ignore) { }
                }
            names[i] = n;
            if (n == null) all = false;
        }
        if (all) return names;
        for (java.lang.annotation.Annotation a : c.getAnnotations())
            if (a.annotationType().getSimpleName().equals("ConstructorProperties")) {
                try { String[] v = (String[]) a.annotationType().getMethod("value").invoke(a); if (v.length == ps.length) return v; } catch (Exception ignore) { }
            }
        List<Field> fs = new ArrayList<>();
        for (Field f : raw.getDeclaredFields()) if (!Modifier.isStatic(f.getModifiers()) && !f.isSynthetic()) fs.add(f);
        if (fs.size() == ps.length) {
            Class<?>[] pt = c.getParameterTypes();
            String[] fn = new String[ps.length];
            for (int i = 0; i < ps.length; i++) {
                if (fs.get(i).getType() != pt[i]) return null;
                fn[i] = fs.get(i).getName();
            }
            return fn;
        }
        return null;
    }

    static Object defaultOf(Class<?> p) {
        if (p == boolean.class) return false;
        if (p == char.class) return 'a';
        if (p == long.class) return 0L;
        if (p == int.class) return 0;
        if (p == short.class) return (short) 0;
        if (p == byte.class) return (byte) 0;
        if (p == float.class) return 0f;
        return 0d;
    }

    // ------------------------------------------------------------------ JSON
    static final class MiniJson {
        private final String s;
        private int i = 0;
        private MiniJson(String s) { this.s = s; }

        static Object parse(String s) {
            MiniJson p = new MiniJson(s);
            Object v = p.value();
            p.ws();
            if (p.i != s.length()) throw new IllegalArgumentException("trailing characters at " + p.i);
            return v;
        }
        private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private IllegalArgumentException err(String m) { return new IllegalArgumentException(m + " at " + i); }
        private void expect(char c) { ws(); if (i >= s.length() || s.charAt(i) != c) throw err("expected '" + c + "'"); i++; }

        private Object value() {
            ws();
            if (i >= s.length()) throw err("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return num();
        }
        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{'); ws();
            if (s.charAt(i) == '}') { i++; return m; }
            while (true) {
                ws(); String k = str(); expect(':');
                m.put(k, value()); ws();
                char c = s.charAt(i++);
                if (c == '}') return m;
                if (c != ',') throw err("expected ',' or '}'");
            }
        }
        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            expect('['); ws();
            if (s.charAt(i) == ']') { i++; return l; }
            while (true) {
                l.add(value()); ws();
                char c = s.charAt(i++);
                if (c == ']') return l;
                if (c != ',') throw err("expected ',' or ']'");
            }
        }
        private String str() {
            ws();
            if (s.charAt(i) != '"') throw err("expected string");
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c != '\\') { sb.append(c); continue; }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
                    default: sb.append(e);
                }
            }
        }
        private BigDecimal num() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            if (st == i) throw err("unexpected character '" + s.charAt(i) + "'");
            return new BigDecimal(s.substring(st, i));
        }
    }
}