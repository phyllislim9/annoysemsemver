package youtube.api.resources;

import java.lang.reflect.*;
import java.util.*;

/**
 * Factory for GenericSSVChk (--factory-src). Builds the V1 / V2 YouTubeResource (class names come from the system
 * properties ssv.v1 / ssv.v2 that GenericSSVChk sets). The class is a singleton with a private constructor, which
 * takes the project's real YouTubeRepositoryImpl; the factory calls that constructor directly (a fresh resource for
 * every probe) and fakes nothing, so V1 and V2 read the same repository data.
 *
 * The argument hook supplies what the checker cannot invent:
 *   - SearchRequest objects (the query bean) with a spread of valid and invalid combinations of the parameters
 *     list(...) validates (part, type, maxResults, order, regionCode, dates, location, ...). Fields are set through
 *     the bean's setters, or the field itself if there is no setter; a name the bean does not have is skipped.
 *   - an HttpServletRequest whose getLocale() answers Locale.US (list(...) passes the locale to the repository).
 * The numbers are kept small on purpose: SearchRequest candidates x request candidates stays within the checker's
 * input budget, so every combination is tried rather than a sample.
 */
public class YouTubeResourceFactory {

    // ------------------------------------------------------------------ hook: hand-built arguments
    public static List<Object> candidates(Class<?> t) {
        List<Object> r = new ArrayList<Object>();
        try {
            if (t.getName().equals("youtube.api.model.SearchRequest")) {
                r.add(req(t, "part", "snippet"));
                r.add(req(t, "part", "id", "maxResults", 3));
                r.add(req(t, "part", "snippet,id", "type", "video", "maxResults", 2, "order", "date"));
                r.add(req(t, "part", "snippet", "type", "channel,playlist"));
                r.add(req(t, "part", "bogus"));
                r.add(req(t, "part", "snippet", "maxResults", 99));
                r.add(req(t, "part", "snippet", "regionCode", "ES"));
                r.add(req(t, "part", "snippet", "regionCode", "ZZ"));
                r.add(req(t, "part", "id", "order", "title", "publishedAfterStr", "2020-01-01T00:00:00Z"));
            } else if (t.getName().equals("javax.servlet.http.HttpServletRequest")) {
                r.add(Proxy.newProxyInstance(t.getClassLoader(), new Class<?>[]{t}, (p, m, a) -> {
                    switch (m.getName()) {
                        case "getLocale": return Locale.US;
                        case "toString": return "stub-request";
                        case "hashCode": return 5;
                        case "equals": return p == a[0];
                    }
                    Class<?> rt = m.getReturnType();
                    return rt == boolean.class ? (Object) false : rt.isPrimitive() ? (Object) 0 : null;
                }));
            }
        } catch (Exception e) { throw new IllegalStateException(e); }
        return r;
    }

    static Object req(Class<?> t, Object... kv) throws Exception {
        Constructor<?> k = t.getDeclaredConstructor();
        k.setAccessible(true);
        Object o = k.newInstance();
        for (int i = 0; i < kv.length; i += 2) set(o, (String) kv[i], kv[i + 1]);
        return o;
    }
    static void set(Object o, String name, Object value) {
        String setter = "set" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method m : o.getClass().getMethods())
            if (m.getName().equals(setter) && m.getParameterCount() == 1) {
                try { m.invoke(o, convert(value, m.getParameterTypes()[0])); return; } catch (Exception ignore) { }
            }
        for (Class<?> c = o.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                f.set(o, convert(value, f.getType()));
                return;
            } catch (NoSuchFieldException ignore) { }
            catch (Exception e) { return; }
        }
    }
    static Object convert(Object v, Class<?> t) {
        if (v instanceof Number && (t == long.class || t == Long.class)) return ((Number) v).longValue();
        if (v instanceof Number && (t == int.class || t == Integer.class)) return ((Number) v).intValue();
        if (v instanceof Number && (t == short.class || t == Short.class)) return ((Number) v).shortValue();
        return v;
    }

    // ------------------------------------------------------------------ building the resource
    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    /** the repository stamps every result with random "etag" strings (RepositoryUtils.generateEtag). A generator that always answers 0 makes
     *  every etag the same string, whichever version asks and in whatever order, so a random string is not mistaken for a behaviour difference */
    static final class FixedRandom extends java.security.SecureRandom {
        @Override public int nextInt(int bound) { return 0; }
    }
    static void fixRandomness() {
        try {
            Field f = Class.forName("youtube.api.util.RepositoryUtils").getDeclaredField("random");
            f.setAccessible(true);
            f.set(null, new FixedRandom());
        } catch (Throwable t) {
            System.err.println("[factory] could not fix the repository's random generator (results will carry different random etags): " + t);
        }
    }

    private static Object build(String className) throws Exception {
        fixRandomness();
        Constructor<?> k = Class.forName(className).getDeclaredConstructor();
        k.setAccessible(true);
        return k.newInstance();
    }
}
