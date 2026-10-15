package com.nikita23830.minecraftd3d.rfb;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

public final class ModJarFilter {
    private ModJarFilter() {}

    static final class JarInfo {
        String tweak;
        final List<String> modids = new ArrayList<String>();
        final List<String> entries = new ArrayList<String>();

        boolean hasPrefix(String p) {
            for (String e : entries) if (e.startsWith(p)) return true;
            return false;
        }
    }

    static final class Rule {
        String name, reason, tweakPrefix, modid, withEntry, withoutEntry;
        final List<String> entryPrefixes = new ArrayList<String>();

        boolean matches(JarInfo j) {
            boolean anyId = false, idOk = false;
            if (tweakPrefix != null) { anyId = true; idOk |= j.tweak != null && j.tweak.startsWith(tweakPrefix); }
            if (modid != null) { anyId = true; idOk |= j.modids.contains(modid); }
            for (String p : entryPrefixes) { anyId = true; idOk |= j.hasPrefix(p); }
            if (!anyId || !idOk) return false;
            if (withEntry != null && !j.entries.contains(withEntry)) return false;
            if (withoutEntry != null && j.entries.contains(withoutEntry)) return false;
            return true;
        }
    }

    private static final Pattern MODID = Pattern.compile("\"modid\"\\s*:\\s*\"([^\"]+)\"");
    private static final Map<String, JarInfo> cache = new HashMap<String, JarInfo>();
    private static final LinkedHashSet<String> excluded = new LinkedHashSet<String>();
    private static List<Rule> rules;
    private static String rulesSource;
    private static boolean announced;

    public static boolean optifineLoaded() {
        return Boolean.getBoolean("minecraftd3d.optifineLoaded");
    }

    public static File[] filter(File[] files) {
        if (files == null) return null;
        if (rules == null) loadRules();
        List<File> keep = new ArrayList<File>(files.length);
        boolean changed = false;
        for (File f : files) {
            JarInfo j = f != null && f.isFile() && f.getName().toLowerCase().matches(".*\\.(jar|zip)$") ? info(f) : null;
            Rule r = null;
            if (j != null) for (Rule x : rules) if (x.matches(j)) { r = x; break; }
            if (r != null) {
                changed = true;
                if (excluded.add(f.getAbsolutePath())) log("ausgeschlossen: " + f.getName() + " — " + r.name + (r.reason != null ? ": " + r.reason : ""));
            } else {
                keep.add(f);
                if (j != null && (j.tweak != null && j.tweak.startsWith("optifine.") || j.hasPrefix("optifine/")))
                    System.setProperty("minecraftd3d.optifineLoaded", "true");
            }
        }
        if (!announced) {
            announced = true;
            log("Modus " + (MinecraftD3DRfbPlugin.ENABLED ? "D3D11" : "OpenGL") + ", Regeln: " + rulesSource + " (" + rules.size() + ")");
        }
        return changed ? keep.toArray(new File[0]) : files;
    }

    private static void loadRules() {
        String section = MinecraftD3DRfbPlugin.ENABLED ? "d3d11" : "opengl";
        File override = new File("config/minecraftd3d-mods-exclude.json");
        try {
            if (override.isFile()) {
                try (Reader r = new InputStreamReader(new FileInputStream(override), StandardCharsets.UTF_8)) {
                    rules = parse(r, section);
                    rulesSource = override.getPath();
                    return;
                }
            }
        } catch (Throwable t) {
            log("Ausschlussliste nicht gelesen (" + t + ") – eingebaute Regeln");
        }
        rules = builtin();
        rulesSource = "eingebaut";
    }

    private static List<Rule> parse(Reader r, String section) {
        JsonObject root = new JsonParser().parse(r).getAsJsonObject();
        List<Rule> out = new ArrayList<Rule>();
        JsonArray items = root.has(section) ? root.getAsJsonArray(section) : new JsonArray();
        for (JsonElement e : items) {
            JsonObject o = e.getAsJsonObject();
            JsonObject m = o.has("match") ? o.getAsJsonObject("match") : new JsonObject();
            Rule x = new Rule();
            x.name = str(o, "name") != null ? str(o, "name") : str(o, "modid");
            x.reason = str(o, "reason");
            x.tweakPrefix = str(m, "manifestTweakClassPrefix");
            x.modid = str(m, "mcmodModid");
            x.withEntry = str(m, "withEntry");
            x.withoutEntry = str(m, "withoutEntry");
            if (m.has("anyEntryPrefix")) {
                JsonElement p = m.get("anyEntryPrefix");
                if (p.isJsonArray()) for (JsonElement q : p.getAsJsonArray()) x.entryPrefixes.add(q.getAsString());
                else x.entryPrefixes.add(p.getAsString());
            }
            out.add(x);
        }
        return out;
    }

    private static String str(JsonObject o, String k) {
        return o.has(k) && o.get(k).isJsonPrimitive() ? o.get(k).getAsString() : null;
    }

    private static List<Rule> builtin() {
        List<Rule> out = new ArrayList<Rule>();
        if (MinecraftD3DRfbPlugin.ENABLED) {
            Rule of = new Rule();
            of.name = "OptiFine";
            of.tweakPrefix = "optifine.";
            of.entryPrefixes.add("optifine/");
            out.add(of);
            Rule nd = new Rule();
            nd.name = "Neodymium";
            nd.modid = "neodymium";
            nd.entryPrefixes.add("makamys/neodymium/");
            out.add(nd);
        }
        return out;
    }

    static JarInfo info(File f) {
        String key = f.getAbsolutePath() + "|" + f.length() + "|" + f.lastModified();
        JarInfo j = cache.get(key);
        if (j == null) {
            j = read(f);
            cache.put(key, j);
        }
        return j;
    }

    private static JarInfo read(File f) {
        JarInfo j = new JarInfo();
        try (JarFile jar = new JarFile(f)) {
            Manifest mf = jar.getManifest();
            j.tweak = mf != null ? mf.getMainAttributes().getValue("TweakClass") : null;
            JarEntry info = jar.getJarEntry("mcmod.info");
            if (info != null) {
                try (InputStream in = jar.getInputStream(info)) {
                    Matcher m = MODID.matcher(new String(readAll(in), StandardCharsets.UTF_8));
                    while (m.find()) j.modids.add(m.group(1));
                }
            }
            for (Enumeration<JarEntry> en = jar.entries(); en.hasMoreElements(); ) j.entries.add(en.nextElement().getName());
        } catch (Exception e) {
        }
        return j;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
        return out.toByteArray();
    }

    private static PrintWriter logFile;

    private static void log(String s) {
        System.out.println("[MinecraftD3D] Mods: " + s);
        try {
            if (logFile == null) {
                new File("logs").mkdirs();
                logFile = new PrintWriter(new OutputStreamWriter(new FileOutputStream("logs/minecraftd3d-mods.log"), StandardCharsets.UTF_8), true);
            }
            logFile.println(s);
        } catch (Exception ignored) {
        }
    }
}
