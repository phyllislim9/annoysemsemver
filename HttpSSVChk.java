import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public class HttpSSVChk {

    // ------------------------------------------------------------------ config
    static String V1, V2, AUTH_NAME, AUTH_VALUE;
    static String SPEC1, SPEC2, PROBES_FILE;
    static Pattern IGNORE = Pattern.compile("(?i)^(ts|timestamp|date|time|created.*|updated.*|modified.*|uuid|token|etag|trace.*|requestId|expires.*)$");
    static Pattern SKIP, ONLY;
    static int TIMEOUT_S = 5, MAX_PER_ENDPOINT = 24;
    static boolean OUTPUT_EXPAND_MINOR = true;
    static final List<String> SEED_STR = new ArrayList<>();
    static HttpClient http;

    public static void main(String[] a) throws Exception {
        for (String s : a) {
            if (s.startsWith("--v1=")) V1 = trim(s.substring(5));
            else if (s.startsWith("--v2=")) V2 = trim(s.substring(5));
            else if (s.startsWith("--spec1=")) SPEC1 = s.substring(8);
            else if (s.startsWith("--spec2=")) SPEC2 = s.substring(8);
            else if (s.startsWith("--probes=")) PROBES_FILE = s.substring(9);
            else if (s.startsWith("--ignore=")) IGNORE = Pattern.compile(s.substring(9));
            else if (s.startsWith("--timeout=")) TIMEOUT_S = Integer.parseInt(s.substring(10));
            else if (s.startsWith("--max-per-endpoint=")) MAX_PER_ENDPOINT = Integer.parseInt(s.substring(19));
            else if (s.startsWith("--skip=")) SKIP = Pattern.compile(s.substring(7));
            else if (s.startsWith("--only=")) ONLY = Pattern.compile(s.substring(7));
            else if (s.startsWith("--output-expand=")) OUTPUT_EXPAND_MINOR = s.substring(16).equals("minor");
            else if (s.startsWith("--seed=")) SEED_STR.add(s.substring(7));
            else if (s.startsWith("--auth=")) parseAuth(s.substring(7));
            else { System.err.println("unknown arg " + s); System.exit(2); }
        }
        if (V1 == null || V2 == null) {
            System.err.println("Usage: java HttpSSVChk --v1=URL --v2=URL [--auth=..] [--spec1=..] [--spec2=..] [--probes=file.json] ...");
            System.exit(2);
        }
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(TIMEOUT_S)).build();

        Map<String, Endpoint> e1 = loadSpec(V1, SPEC1), e2 = loadSpec(V2, SPEC2);
        System.err.println("[spec] V1 endpoints=" + e1.size() + "  V2 endpoints=" + e2.size());
        Report r = new Report();
        signatureDiff(e1, e2, r);

        // ---- build probes from V1's spec (V1 is the oracle) + hand-written probes
        List<Probe> probes = new ArrayList<>();
        for (Endpoint e : e1.values()) {
            String key = e.method + " " + e.path;
            if (SKIP != null && SKIP.matcher(key).find()) continue;
            if (ONLY != null && !ONLY.matcher(key).find()) continue;
            if (!e2.containsKey(e.method + " " + e.path)) continue;  // removed: already a signature finding
            probes.addAll(e.probes());
        }
        if (PROBES_FILE != null) probes.addAll(loadExtraProbes(PROBES_FILE));
        System.err.println("[probes] " + probes.size() + " generated");

        // ---- lockstep execution
        Map<String, List<Pair>> byEndpoint = new LinkedHashMap<>();
        int n = 0, auth401 = 0;
        for (Probe p : probes) {
            Resp r1 = send(V1, p), r2 = send(V2, p);
            if (r1.status == 401 || r1.status == 403) auth401++;
            byEndpoint.computeIfAbsent(p.endpointKey, k -> new ArrayList<>()).add(new Pair(p, r1, r2));
            if (++n % 25 == 0) System.err.println("  [probe] " + n + "/" + probes.size());
        }
        if (!probes.isEmpty() && auth401 * 2 > probes.size())
            r.warnings.add("more than half of V1's probes returned 401/403 -- check --auth; results are mostly vacuous");
        for (Map.Entry<String, List<Pair>> en : byEndpoint.entrySet()) classify(en.getKey(), en.getValue(), r);
        r.aggregate();
        r.print();
        System.exit(r.verdict.equals("REFACTOR") ? 0 : 1);
    }

    static String trim(String u) { return u.endsWith("/") ? u.substring(0, u.length() - 1) : u; }
    static void parseAuth(String s) {
        if (s.startsWith("basic:")) {
            AUTH_NAME = "Authorization";
            AUTH_VALUE = "Basic " + Base64.getEncoder().encodeToString(s.substring(6).getBytes(StandardCharsets.UTF_8));
        } else if (s.startsWith("bearer:")) { AUTH_NAME = "Authorization"; AUTH_VALUE = "Bearer " + s.substring(7); }
        else if (s.startsWith("header:")) { String[] p = s.substring(7).split(":", 2); AUTH_NAME = p[0]; AUTH_VALUE = p[1]; }
    }

    // ------------------------------------------------------------------ model
    static final class Param {
        String name, in, type, format; boolean required; List<Object> enums;
        String sig() { return name + ":" + in + ":" + type + (required ? "!" : "?"); }
    }
    static final class Endpoint {
        String method, path; List<Param> params = new ArrayList<>(); Object bodySchema; Map<String, Object> defs;
        Set<String> bodyFields = new TreeSet<>();
        String key() { return method + " " + path; }

        List<Probe> probes() {
            List<Probe> out = new ArrayList<>();
            // baseline value for each param, then one-at-a-time variation (OFAT) -- avoids combinatorial blowup
            Map<String, Object> base = new LinkedHashMap<>();
            Map<String, List<Object>> cands = new LinkedHashMap<>();
            for (Param p : params) {
                List<Object> c = candidates(p.type, p.format, p.enums);
                cands.put(p.in + ":" + p.name, c);
                if (p.required || p.in.equals("path") || true) base.put(p.in + ":" + p.name, c.get(0));
            }
            Object baseBody = bodySchema == null ? null : sample(bodySchema, 0);
            out.add(make(base, baseBody));
            for (Map.Entry<String, List<Object>> c : cands.entrySet()) {
                for (int i = 1; i < c.getValue().size() && out.size() < MAX_PER_ENDPOINT; i++) {
                    Map<String, Object> m = new LinkedHashMap<>(base); m.put(c.getKey(), c.getValue().get(i));
                    out.add(make(m, baseBody));
                }
                // optional/query param omitted
                if (c.getKey().startsWith("query:") && out.size() < MAX_PER_ENDPOINT) {
                    Map<String, Object> m = new LinkedHashMap<>(base); m.remove(c.getKey()); out.add(make(m, baseBody));
                }
            }
            if (baseBody instanceof Map) {
                Map<?, ?> bm = (Map<?, ?>) baseBody;
                for (Object k : bm.keySet()) {
                    for (String mode : new String[]{"null", "omit", "empty", "neg", "long"}) {
                        if (out.size() >= MAX_PER_ENDPOINT) break;
                        Map<String, Object> nb = new LinkedHashMap<>();
                        for (Map.Entry<?, ?> en : bm.entrySet()) nb.put((String) en.getKey(), en.getValue());
                        Object v = nb.get(k);
                        switch (mode) {
                            case "null": nb.put((String) k, null); break;
                            case "omit": nb.remove(k); break;
                            case "empty": if (v instanceof String) nb.put((String) k, ""); else continue; break;
                            case "neg": if (v instanceof Number) nb.put((String) k, -1); else continue; break;
                            case "long": if (v instanceof String) nb.put((String) k, "x".repeat(1000)); else continue; break;
                        }
                        out.add(make(base, nb));
                    }
                }
            }
            return out;
        }
        Probe make(Map<String, Object> vals, Object body) {
            String p = path; StringBuilder q = new StringBuilder();
            for (Map.Entry<String, Object> en : vals.entrySet()) {
                String[] k = en.getKey().split(":", 2);
                String v = en.getValue() == null ? "" : String.valueOf(en.getValue());
                if (k[0].equals("path")) p = p.replace("{" + k[1] + "}", enc(v));
                else if (k[0].equals("query")) q.append(q.length() == 0 ? "?" : "&").append(enc(k[1])).append('=').append(enc(v));
            }
            p = p.replaceAll("\\{[^}]+\\}", "1");
            Probe pr = new Probe();
            pr.method = method; pr.url = p + q; pr.endpointKey = key();
            pr.body = body == null ? null : Json.write(body);
            pr.desc = method + " " + p + q + (pr.body != null ? " body=" + abbreviate(pr.body, 80) : "");
            return pr;
        }
        Object sample(Object schema, int depth) {
            Map<String, Object> s = deref(schema);
            if (s == null || depth > 4) return null;
            if (s.containsKey("enum")) return ((List<?>) s.get("enum")).get(0);
            String t = String.valueOf(s.get("type"));
            if (s.containsKey("properties") || t.equals("object")) {
                Map<String, Object> o = new LinkedHashMap<>();
                Object props = s.get("properties");
                if (props instanceof Map) for (Map.Entry<?, ?> en : ((Map<?, ?>) props).entrySet())
                    o.put((String) en.getKey(), sample(en.getValue(), depth + 1));
                return o;
            }
            if (t.equals("array")) { List<Object> l = new ArrayList<>(); Object it = sample(s.get("items"), depth + 1); if (it != null) l.add(it); return l; }
            return candidates(t, String.valueOf(s.get("format")), null).get(0);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> deref(Object schema) {
            if (!(schema instanceof Map)) return null;
            Map<String, Object> s = (Map<String, Object>) schema;
            Object ref = s.get("$ref");
            if (ref != null) {
                String r = ref.toString(); String name = r.substring(r.lastIndexOf('/') + 1);
                Object d = defs == null ? null : defs.get(name);
                return d instanceof Map ? (Map<String, Object>) d : null;
            }
            return s;
        }
    }

    static List<Object> candidates(String type, String format, List<Object> enums) {
        List<Object> c = new ArrayList<>();
        if (enums != null && !enums.isEmpty()) { c.addAll(enums); c.add("__INVALID_ENUM__"); return c; }
        switch (type == null ? "string" : type) {
            case "integer": c.addAll(Arrays.asList(1L, 0L, -1L, 2L, 999999L, 2147483647L));
                for (String s : SEED_STR) try { c.add(Long.parseLong(s)); } catch (Exception ignored) {} break;
            case "number": c.addAll(Arrays.asList(1.0, 0.0, -1.0, 1e9)); break;
            case "boolean": c.addAll(Arrays.asList(true, false)); break;
            default: c.addAll(Arrays.asList("test", "", " ", "x".repeat(300), "null", "' OR 1=1 --"));
                c.addAll(SEED_STR);
        }
        if ("date".equals(format)) { c.add(0, "2024-01-01"); c.add("not-a-date"); }
        if ("date-time".equals(format)) { c.add(0, "2024-01-01T00:00:00Z"); c.add("not-a-date"); }
        if ("email".equals(format)) c.add(0, "a@b.com");
        if ("string".equals(type) && "email".equals(format)) c.add("not-an-email");
        return c;
    }

    static final class Probe { String method, url, body, desc, endpointKey; }
    static final class Resp {
        int status; String body = ""; boolean timeout, connError;
        String cls() { return timeout ? "HANG" : connError || status >= 500 ? "CRASH" : status >= 400 ? "REJ" : "VALID"; }
    }
    static final class Pair { Probe p; Resp a, b; Pair(Probe p, Resp a, Resp b) { this.p = p; this.a = a; this.b = b; } }

    static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }
    static String abbreviate(String s, int n) { return s.length() <= n ? s : s.substring(0, n) + "..."; }

    // ------------------------------------------------------------------ spec loading
    @SuppressWarnings("unchecked")
    static Map<String, Endpoint> loadSpec(String base, String specArg) throws Exception {
        String text = null;
        if (specArg != null) {
            text = specArg.startsWith("http") ? get(specArg) : Files.readString(Paths.get(specArg));
        } else {
            for (String path : new String[]{"/v3/api-docs", "/v2/api-docs", "/swagger.json", "/openapi.json", "/api-docs", "/v2/swagger.json"}) {
                try { String t = get(base + path); if (t != null && t.contains("\"paths\"")) { text = t; break; } } catch (Exception ignored) {}
            }
        }
        if (text == null) throw new IllegalStateException("no OpenAPI/Swagger spec found at " + base
                + " -- pass --spec1/--spec2, or enable springfox/springdoc, or generate one from EvoMaster's swagger.json");
        Map<String, Object> root = (Map<String, Object>) Json.parse(text);
        Map<String, Object> defs = new HashMap<>();
        if (root.get("definitions") instanceof Map) defs.putAll((Map<String, Object>) root.get("definitions"));
        if (root.get("components") instanceof Map && ((Map<String, Object>) root.get("components")).get("schemas") instanceof Map)
            defs.putAll((Map<String, Object>) ((Map<String, Object>) root.get("components")).get("schemas"));
        Map<String, Endpoint> out = new TreeMap<>();
        Map<String, Object> paths = (Map<String, Object>) root.get("paths");
        for (Map.Entry<String, Object> pe : paths.entrySet()) {
            Map<String, Object> ops = (Map<String, Object>) pe.getValue();
            List<Object> common = ops.get("parameters") instanceof List ? (List<Object>) ops.get("parameters") : new ArrayList<>();
            for (Map.Entry<String, Object> oe : ops.entrySet()) {
                String m = oe.getKey().toUpperCase();
                if (!Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH").contains(m)) continue;
                Map<String, Object> op = (Map<String, Object>) oe.getValue();
                Endpoint e = new Endpoint(); e.method = m; e.path = pe.getKey(); e.defs = defs;
                List<Object> ps = new ArrayList<>(common);
                if (op.get("parameters") instanceof List) ps.addAll((List<Object>) op.get("parameters"));
                for (Object po : ps) {
                    Map<String, Object> pm = (Map<String, Object>) po;
                    if (pm.containsKey("$ref")) { Endpoint tmp = e; Map<String, Object> d = tmp.deref(pm); if (d == null) continue; pm = d; }
                    String in = String.valueOf(pm.get("in"));
                    if (in.equals("body")) { e.bodySchema = pm.get("schema"); continue; }
                    if (!in.equals("path") && !in.equals("query")) continue;
                    Param p = new Param(); p.name = String.valueOf(pm.get("name")); p.in = in;
                    p.required = Boolean.TRUE.equals(pm.get("required")) || in.equals("path");
                    Map<String, Object> sch = pm.get("schema") instanceof Map ? (Map<String, Object>) pm.get("schema") : pm;
                    p.type = String.valueOf(sch.get("type")); p.format = sch.get("format") == null ? null : String.valueOf(sch.get("format"));
                    p.enums = sch.get("enum") instanceof List ? (List<Object>) sch.get("enum") : null;
                    e.params.add(p);
                }
                if (op.get("requestBody") instanceof Map) {
                    Map<String, Object> rb = (Map<String, Object>) op.get("requestBody");
                    Map<String, Object> content = (Map<String, Object>) rb.get("content");
                    if (content != null && content.get("application/json") instanceof Map)
                        e.bodySchema = ((Map<String, Object>) content.get("application/json")).get("schema");
                }
                if (e.bodySchema != null) collectFields(e, e.bodySchema, "", 0);
                out.put(e.key(), e);
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void collectFields(Endpoint e, Object schema, String prefix, int depth) {
        Map<String, Object> s = e.deref(schema);
        if (s == null || depth > 3) return;
        if (s.get("properties") instanceof Map)
            for (Map.Entry<String, Object> en : ((Map<String, Object>) s.get("properties")).entrySet()) {
                Map<String, Object> ps = e.deref(en.getValue());
                e.bodyFields.add(prefix + en.getKey() + ":" + (ps == null ? "?" : ps.get("type")));
                collectFields(e, en.getValue(), prefix + en.getKey() + ".", depth + 1);
            }
    }

    static String get(String url) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(TIMEOUT_S)).GET();
        if (AUTH_NAME != null) b.header(AUTH_NAME, AUTH_VALUE);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.statusCode() == 200 ? r.body() : null;
    }

    @SuppressWarnings("unchecked")
    static List<Probe> loadExtraProbes(String file) throws Exception {
        List<Probe> out = new ArrayList<>();
        for (Object o : (List<Object>) Json.parse(Files.readString(Paths.get(file)))) {
            Map<String, Object> m = (Map<String, Object>) o; Probe p = new Probe();
            p.method = String.valueOf(m.get("method")).toUpperCase(); p.url = String.valueOf(m.get("path"));
            p.body = m.get("body") == null ? null : (m.get("body") instanceof String ? (String) m.get("body") : Json.write(m.get("body")));
            p.endpointKey = "EXTRA " + p.method + " " + p.url.replaceAll("\\?.*", "");
            p.desc = p.method + " " + p.url + (p.body != null ? " body=" + abbreviate(p.body, 80) : "");
            out.add(p);
        }
        return out;
    }

    // ------------------------------------------------------------------ http
    static Resp send(String base, Probe p) {
        Resp r = new Resp();
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + p.url)).timeout(Duration.ofSeconds(TIMEOUT_S));
            if (AUTH_NAME != null) b.header(AUTH_NAME, AUTH_VALUE);
            if (p.body != null) b.header("Content-Type", "application/json");
            b.method(p.method, p.body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(p.body));
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            r.status = resp.statusCode(); r.body = resp.body() == null ? "" : resp.body();
        } catch (HttpTimeoutException e) { r.timeout = true;
        } catch (IllegalArgumentException e) { r.status = 0; r.connError = false; r.status = 400; // un-encodable probe: both sides identical
        } catch (Exception e) { r.connError = true; }
        return r;
    }

    // ------------------------------------------------------------------ stage 1
    static void signatureDiff(Map<String, Endpoint> a, Map<String, Endpoint> b, Report r) {
        for (String k : a.keySet()) if (!b.containsKey(k)) r.removed.add(k);
        for (String k : b.keySet()) if (!a.containsKey(k)) r.added.add(k);
        for (String k : a.keySet()) {
            Endpoint x = a.get(k), y = b.get(k); if (y == null) continue;
            Map<String, Param> px = new TreeMap<>(), py = new TreeMap<>();
            for (Param p : x.params) px.put(p.in + ":" + p.name, p);
            for (Param p : y.params) py.put(p.in + ":" + p.name, p);
            for (String pk : px.keySet()) {
                if (!py.containsKey(pk)) r.changed.add(k + " param removed: " + pk);
                else if (!px.get(pk).sig().equals(py.get(pk).sig())) r.changed.add(k + " param " + px.get(pk).sig() + " -> " + py.get(pk).sig());
            }
            for (String pk : py.keySet()) if (!px.containsKey(pk)) {
                if (py.get(pk).required) r.changed.add(k + " new REQUIRED param: " + pk); else r.addedOptional.add(k + " optional param: " + pk);
            }
            Set<String> removedF = new TreeSet<>(x.bodyFields); removedF.removeAll(y.bodyFields);
            Set<String> addedF = new TreeSet<>(y.bodyFields); addedF.removeAll(x.bodyFields);
            if (!removedF.isEmpty()) r.changed.add(k + " request body fields removed/retyped: " + removedF);
            if (!addedF.isEmpty()) r.addedOptional.add(k + " request body fields added: " + addedF);
        }
    }

    // ------------------------------------------------------------------ stage 2
    static void classify(String endpoint, List<Pair> pairs, Report r) {
        Report.EP ep = new Report.EP(endpoint);
        Set<String> distinct1 = new HashSet<>(), distinct2 = new HashSet<>();
        int okBoth = 0;
        for (Pair pr : pairs) {
            ep.n++;
            String c1 = pr.a.cls(), c2 = pr.b.cls();
            String t = c1 + "->" + c2;
            if (c1.equals("HANG") && !c2.equals("HANG")) ep.repair = true;           // hang fixed
            if (c1.equals("VALID") && (c2.equals("CRASH") || c2.equals("HANG"))) {
                if (c2.equals("HANG")) ep.liveness = true; else ep.introduce = true; ep.ex(t, pr, pr.b.status);
            } else if (c1.equals("VALID") && c2.equals("REJ")) { ep.contract = true; ep.ex(t, pr, pr.b.status); }
            else if (c1.equals("REJ") && c2.equals("VALID")) { ep.expand = true; ep.ex(t, pr, pr.b.status); }
            else if (c1.equals("REJ") && c2.equals("CRASH")) { ep.introduce = true; ep.ex(t, pr, pr.b.status); }
            else if (c1.equals("CRASH") && (c2.equals("VALID") || c2.equals("REJ"))) { ep.repair = true; ep.ex(t, pr, pr.b.status); }
            else if (c1.equals("VALID") && c2.equals("VALID")) {
                okBoth++;
                Object j1 = Json.tryParse(pr.a.body), j2 = Json.tryParse(pr.b.body);
                String n1 = Json.write(Json.normalize(j1, IGNORE)), n2 = Json.write(Json.normalize(j2, IGNORE));
                distinct1.add(n1); distinct2.add(n2);
                if (!n1.equals(n2)) {
                    String why = Json.diffKind(Json.normalize(j1, IGNORE), Json.normalize(j2, IGNORE));
                    if (why.startsWith("WEAKEN")) ep.weakened = true;
                    else if (why.startsWith("EXPAND")) ep.outputExpand = true;
                    else ep.permute = true;
                    ep.ex("VALID->VALID " + why, pr, pr.b.status);
                }
            } else if (c1.equals("REJ") && c2.equals("REJ") && pr.a.status != pr.b.status) {
                ep.permute = true; ep.ex("REJ " + pr.a.status + "->" + pr.b.status, pr, pr.b.status);  // different rejection code
            }
        }
        if (okBoth >= 3 && distinct1.size() >= 2 && distinct2.size() == 1) { ep.weakened = true; ep.exs.add("collapse: V1 produced " + distinct1.size() + " distinct bodies, V2 a single constant"); }
        r.eps.add(ep);
    }

    // ------------------------------------------------------------------ report
    static final class Report {
        List<String> removed = new ArrayList<>(), added = new ArrayList<>(), changed = new ArrayList<>(),
                addedOptional = new ArrayList<>(), warnings = new ArrayList<>(), reasons = new ArrayList<>();
        List<EP> eps = new ArrayList<>(); String verdict;
        static final class EP {
            String name; int n; boolean introduce, liveness, contract, expand, repair, permute, weakened, outputExpand;
            List<String> exs = new ArrayList<>();
            EP(String n) { name = n; }
            void ex(String t, Pair p, int st) { if (exs.size() < 3) exs.add(t + "  " + p.p.desc + "  (V1=" + p.a.status + ", V2=" + p.b.status + ")"); }
        }
        void aggregate() {
            boolean intro = false, live = false, contr = false, exp = false, rep = false, perm = false, weak = false, oexp = false;
            for (EP e : eps) { intro |= e.introduce; live |= e.liveness; contr |= e.contract; exp |= e.expand; rep |= e.repair; perm |= e.permute; weak |= e.weakened; oexp |= e.outputExpand; }
            boolean major = !removed.isEmpty() || !changed.isEmpty() || intro || live || contr || weak;
            boolean minor = !added.isEmpty() || !addedOptional.isEmpty() || exp || (oexp && OUTPUT_EXPAND_MINOR);
            if (major) {
                verdict = "MAJOR";
                if (!removed.isEmpty()) reasons.add("endpoint(s) removed: " + removed);
                if (!changed.isEmpty()) reasons.add("signature changed: " + changed);
                if (intro) reasons.add("fault introduced: previously valid/rejected input now 5xx");
                if (live) reasons.add("liveness broken: previously terminating request now times out");
                if (contr) reasons.add("input contract: previously accepted input now rejected (4xx)");
                if (weak) reasons.add("output weakened: array shrank / field dropped / collapsed to constant");
            } else if (minor) {
                verdict = "MINOR";
                if (!added.isEmpty()) reasons.add("endpoint(s) added: " + added);
                if (!addedOptional.isEmpty()) reasons.add("optional additions: " + addedOptional);
                if (exp) reasons.add("input expand: previously rejected (4xx) input now accepted");
                if (oexp) reasons.add("output expand: response gained field(s)");
            } else if (perm) { verdict = "PATCH"; reasons.add("response bodies/rejection codes differ on shared valid input(s), nothing breaking observed");
            } else if (rep) { verdict = "CRASH_FIX"; reasons.add("previously failing (5xx/hang) request now succeeds or is cleanly rejected");
            } else { verdict = "REFACTOR"; reasons.add("no observable difference across sampled requests"); }
        }
        void print() {
            String bar = "=".repeat(72);
            System.out.println(bar + "\nHTTP SSVCHK REPORT: " + V1 + "  ->  " + V2 + "\n" + bar);
            System.out.println("\n[Signature diff]\n  removed:  " + removed + "\n  added:    " + added + "\n  changed:  " + changed + "\n  optional additions: " + addedOptional);
            System.out.println("\n[Trace diff]");
            for (EP e : eps) {
                System.out.printf("  %-34s probes=%-3d introduce=%-5b contract=%-5b expand=%-5b repair=%-5b hang=%-5b permute=%-5b weaken=%-5b%n",
                        e.name, e.n, e.introduce, e.contract, e.expand, e.repair, e.liveness, e.permute, e.weakened);
                for (String x : e.exs) System.out.println("        - " + x);
            }
            for (String w : warnings) System.out.println("\n[WARNING] " + w);
            System.out.println("\n[Verdict] " + verdict);
            for (String x : reasons) System.out.println("  - " + x);
            System.out.println(bar);
        }
    }

    // ------------------------------------------------------------------ tiny JSON
    static final class Json {
        static Object tryParse(String s) { try { return parse(s); } catch (Exception e) { return s; } }
        static Object parse(String s) { int[] i = {0}; Object o = val(s, i); ws(s, i); if (i[0] != s.length()) throw new RuntimeException("trailing"); return o; }
        static void ws(String s, int[] i) { while (i[0] < s.length() && Character.isWhitespace(s.charAt(i[0]))) i[0]++; }
        static Object val(String s, int[] i) {
            ws(s, i); char c = s.charAt(i[0]);
            if (c == '{') { Map<String, Object> m = new LinkedHashMap<>(); i[0]++; ws(s, i);
                if (s.charAt(i[0]) == '}') { i[0]++; return m; }
                while (true) { ws(s, i); String k = str(s, i); ws(s, i); i[0]++; m.put(k, val(s, i)); ws(s, i);
                    char d = s.charAt(i[0]++); if (d == '}') return m; if (d != ',') throw new RuntimeException("obj"); } }
            if (c == '[') { List<Object> l = new ArrayList<>(); i[0]++; ws(s, i);
                if (s.charAt(i[0]) == ']') { i[0]++; return l; }
                while (true) { l.add(val(s, i)); ws(s, i); char d = s.charAt(i[0]++); if (d == ']') return l; if (d != ',') throw new RuntimeException("arr"); } }
            if (c == '"') return str(s, i);
            if (s.startsWith("true", i[0])) { i[0] += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i[0])) { i[0] += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i[0])) { i[0] += 4; return null; }
            int st = i[0]; while (i[0] < s.length() && "+-0123456789.eE".indexOf(s.charAt(i[0])) >= 0) i[0]++;
            if (st == i[0]) throw new RuntimeException("bad json");
            String num = s.substring(st, i[0]);
            return num.matches("-?\\d+") ? (Object) Long.parseLong(num) : (Object) Double.parseDouble(num);
        }
        static String str(String s, int[] i) {
            StringBuilder b = new StringBuilder(); i[0]++;
            while (true) { char c = s.charAt(i[0]++);
                if (c == '"') return b.toString();
                if (c == '\\') { char e = s.charAt(i[0]++);
                    switch (e) { case 'n': b.append('\n'); break; case 't': b.append('\t'); break; case 'r': b.append('\r'); break;
                        case 'b': b.append('\b'); break; case 'f': b.append('\f'); break;
                        case 'u': b.append((char) Integer.parseInt(s.substring(i[0], i[0] + 4), 16)); i[0] += 4; break;
                        default: b.append(e); }
                } else b.append(c); }
        }
        @SuppressWarnings("unchecked")
        static String write(Object o) {
            if (o == null) return "null";
            if (o instanceof String) { StringBuilder b = new StringBuilder("\"");
                for (char c : ((String) o).toCharArray()) { switch (c) { case '"': b.append("\\\""); break; case '\\': b.append("\\\\"); break;
                    case '\n': b.append("\\n"); break; case '\r': b.append("\\r"); break; case '\t': b.append("\\t"); break;
                    default: if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c); } }
                return b.append('"').toString(); }
            if (o instanceof Map) { StringBuilder b = new StringBuilder("{"); boolean f = true;
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet()) { if (!f) b.append(','); f = false; b.append(write(e.getKey())).append(':').append(write(e.getValue())); }
                return b.append('}').toString(); }
            if (o instanceof List) { StringBuilder b = new StringBuilder("["); boolean f = true;
                for (Object x : (List<Object>) o) { if (!f) b.append(','); f = false; b.append(write(x)); } return b.append(']').toString(); }
            return String.valueOf(o);
        }
        /** Drop volatile keys (timestamps, ids matching IGNORE) and sort object keys so key order never counts as a difference. */
        @SuppressWarnings("unchecked")
        static Object normalize(Object o, Pattern ignore) {
            if (o instanceof Map) { Map<String, Object> m = new TreeMap<>();
                for (Map.Entry<String, Object> e : ((Map<String, Object>) o).entrySet())
                    if (!ignore.matcher(e.getKey()).find()) m.put(e.getKey(), normalize(e.getValue(), ignore));
                return m; }
            if (o instanceof List) { List<Object> l = new ArrayList<>(); for (Object x : (List<Object>) o) l.add(normalize(x, ignore)); return l; }
            return o;
        }
        /** Classify a body difference: WEAKEN (shrank / key dropped), EXPAND (key added only), else PERMUTE. */
        @SuppressWarnings("unchecked")
        static String diffKind(Object a, Object b) {
            if (a instanceof List && b instanceof List) {
                int x = ((List<?>) a).size(), y = ((List<?>) b).size();
                if (y < x) return "WEAKEN array " + x + " -> " + y;
                if (y > x) return "EXPAND array " + x + " -> " + y;
                return "PERMUTE";
            }
            if (a instanceof Map && b instanceof Map) {
                Set<String> ka = ((Map<String, Object>) a).keySet(), kb = ((Map<String, Object>) b).keySet();
                Set<String> dropped = new TreeSet<>(ka); dropped.removeAll(kb);
                Set<String> gained = new TreeSet<>(kb); gained.removeAll(ka);
                if (!dropped.isEmpty()) return "WEAKEN fields dropped " + dropped;
                for (String k : ka) { String s = diffKind(((Map<String, Object>) a).get(k), ((Map<String, Object>) b).get(k));
                    if (s.startsWith("WEAKEN")) return s; }
                if (!gained.isEmpty()) return "EXPAND fields added " + gained;
                for (String k : ka) { String s = diffKind(((Map<String, Object>) a).get(k), ((Map<String, Object>) b).get(k));
                    if (s.startsWith("EXPAND")) return s; }
            }
            return "PERMUTE";
        }
    }
}
