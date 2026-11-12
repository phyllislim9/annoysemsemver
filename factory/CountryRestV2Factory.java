package eu.fayder.restcountries.v2.rest;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for GenericSSVChk (--factory-src). Builds the V1 / V2 CountryRestV2 (class names come from the system
 * properties ssv.v1 / ssv.v2 that GenericSSVChk sets). The resource has no injected dependencies: it talks to the
 * CountryService singleton, which is the project's real one, so nothing is faked and V1 and V2 see the same data.
 *
 * The only job of this factory is the argument hook: it adds real country codes, currencies, names, regions, ... to the
 * string inputs the checker tries, so that lookups can actually hit a country (otherwise almost every probe would
 * just be a 404 for both versions and output changes could not show).
 *
 * Run the checker with --http-5xx-crash: CountryRestV2 catches every exception and answers HTTP 500, so without the flag
 * a failing lookup would look like an ordinary output.
 */
public class CountryRestV2Factory {

    public static List<Object> candidates(Class<?> t) {
        List<Object> r = new ArrayList<Object>();
        if (t == String.class) {
            for (String s : new String[]{"name;capital", "ES", "EUR", "Europe", "Spain", "34", "ES;FR"}) r.add(s);
        }
        return r;
    }

    public static Object createV1() throws Exception { return Class.forName(System.getProperty("ssv.v1")).getDeclaredConstructor().newInstance(); }
    public static Object createV2() throws Exception { return Class.forName(System.getProperty("ssv.v2")).getDeclaredConstructor().newInstance(); }
}
