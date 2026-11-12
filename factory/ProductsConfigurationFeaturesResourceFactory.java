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

import org.javiermf.features.models.evaluation.EvaluationResult;

/**
 * Factory for GenericSSVChk (--factory-src). Builds the V1 / V2 resource under test (class names come from the
 * system properties ssv.v1 / ssv.v2 that GenericSSVChk sets) and replaces its @Autowired service with a small
 * deterministic fake, IDENTICAL for both versions:
 *   getConfigurationActivedFeaturesNames -> [f1..f5] for every input (non-empty, so list-shaping changes show)
 *   addFeatureFromConfiguration / removeFeatureFromConfiguration -> invalid result iff featureName is null/blank
 *
 * Works whether ProductsConfigurationsService is an interface (dynamic proxy) or a class (a subclass is generated and
 * compiled at run time, calling the smallest constructor with null/zero arguments). Needs a JDK (javax.tools) to run.
 */
public class ProductsConfigurationFeaturesResourceFactory {

    static final String SERVICE = "org.javiermf.features.services.ProductsConfigurationsService";

    // ---- the fake behaviour (called from the generated subclass / proxy)
    public static List<String> names() { return new ArrayList<String>(Arrays.asList("f1", "f2", "f3", "f4", "f5")); }
    public static EvaluationResult verdict(String featureName) {
        EvaluationResult r = new EvaluationResult();
        if (featureName == null || featureName.trim().isEmpty()) {
            r.isValid = false;
            r.evaluationErrorMessages.add("invalid feature name");
        }
        return r;
    }
    public static Object dispatch(String method, Object[] a) {
        if (method.equals("getConfigurationActivedFeaturesNames")) return names();
        if (method.equals("addFeatureFromConfiguration") || method.equals("removeFeatureFromConfiguration")) return verdict((String) a[2]);
        return null;
    }

    public static Object createV1() throws Exception { return build(System.getProperty("ssv.v1")); }
    public static Object createV2() throws Exception { return build(System.getProperty("ssv.v2")); }

    private static Object build(String className) throws Exception {
        Class<?> c = Class.forName(className);
        Object resource = c.getDeclaredConstructor().newInstance();
        Field f = c.getDeclaredField("configurationsService");
        f.setAccessible(true);
        f.set(resource, fakeService(f.getType()));
        return resource;
    }

    private static Class<?> generated;

    private static Object fakeService(Class<?> svc) throws Exception {
        if (svc.isInterface())
            return Proxy.newProxyInstance(svc.getClassLoader(), new Class<?>[]{svc}, (p, m, a) -> dispatch(m.getName(), a));
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
        String fac = ProductsConfigurationFeaturesResourceFactory.class.getCanonicalName();
        for (Method m : svc.getMethods()) {
            String n = m.getName();
            if (!(n.equals("getConfigurationActivedFeaturesNames") || n.equals("addFeatureFromConfiguration") || n.equals("removeFeatureFromConfiguration"))) continue;
            if (Modifier.isFinal(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
            src.append("  @Override public ").append(m.getReturnType().getCanonicalName()).append(' ').append(n).append('(');
            Class<?>[] ps = m.getParameterTypes();
            StringBuilder args = new StringBuilder();
            for (int i = 0; i < ps.length; i++) {
                if (i > 0) { src.append(", "); args.append(", "); }
                src.append(ps[i].getCanonicalName()).append(" a").append(i);
                args.append("a").append(i);
            }
            src.append(") { return (").append(m.getReturnType().getCanonicalName()).append(") ").append(fac)
               .append(".dispatch(\"").append(n).append("\", new Object[]{").append(args).append("}); }\n");
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