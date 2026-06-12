import com.sun.net.httpserver.*;
import javax.tools.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import java.util.stream.*;

public class JavaToolBox {

    // ═══════════════════ Data ═══════════════════

    static class ThrowsInfo {
        final String exception, desc;
        ThrowsInfo(String e, String d) { exception = e; desc = d; }
    }

    static class ParsedJavadoc {
        String description = "";
        LinkedHashMap<String, String> params = new LinkedHashMap<>();
        String returnDesc = "";
        List<ThrowsInfo> throwsList = new ArrayList<>();

        static ParsedJavadoc parse(String raw) {
            var r = new ParsedJavadoc();
            if (raw == null || raw.trim().isEmpty()) return r;
            raw = raw.replaceAll("\\{@(?:code|link|literal|value)[^}]*\\}", "");
            String[] lines = raw.split("\\n");
            var descLines = new ArrayList<String>();
            String curTag = null, curParam = null;
            var buf = new StringBuilder();
            for (String line : lines) {
                String cl = line.replaceAll("^\\s*\\*?\\s?", "").trim();
                if (cl.isEmpty()) { if (curTag != null) buf.append(" "); continue; }
                if (cl.startsWith("@")) {
                    if (curTag != null) flush(r, curTag, curParam, buf.toString().trim());
                    String rest = cl.substring(1).trim();
                    int sp = rest.indexOf(' ');
                    if (sp < 0) { curTag = rest; curParam = null; buf = new StringBuilder(); continue; }
                    curTag = rest.substring(0, sp);
                    rest = rest.substring(sp + 1).trim();
                    if ("param".equals(curTag)) {
                        int sp2 = rest.indexOf(' ');
                        if (sp2 > 0) { curParam = rest.substring(0, sp2); buf = new StringBuilder(rest.substring(sp2 + 1)); }
                        else { curParam = rest; buf = new StringBuilder(); }
                    } else { curParam = null; buf = new StringBuilder(rest); }
                } else if (curTag != null) { buf.append(" ").append(cl); }
                else { descLines.add(cl); }
            }
            if (curTag != null) flush(r, curTag, curParam, buf.toString().trim());
            r.description = String.join(" ", descLines).replaceAll("\\s{2,}", " ").trim();
            return r;
        }

        static void flush(ParsedJavadoc r, String tag, String param, String content) {
            if (content.isEmpty()) return;
            switch (tag) {
                case "param" -> { if (param != null) r.params.put(param, content); }
                case "return", "returns" -> r.returnDesc = content;
                case "throws", "exception" -> {
                    int sp = content.indexOf(' ');
                    if (sp > 0) r.throwsList.add(new ThrowsInfo(content.substring(0, sp), content.substring(sp + 1).trim()));
                    else r.throwsList.add(new ThrowsInfo(content, ""));
                }
            }
        }
    }

    static class ParamInfo {
        final String name, type, desc;
        ParamInfo(String n, String t) { name = n; type = t; desc = ""; }
        ParamInfo(String n, String t, String d) { name = n; type = t; desc = d; }
    }

    static class MethodInfo {
        final String className, methodName, desc, source, returnType, returnDesc, classDesc;
        final ParamInfo[] params;
        final List<ThrowsInfo> throwsList;
        MethodInfo(String cn, String mn, String d, ParamInfo[] p, String rt, String rd, String cd, List<ThrowsInfo> tl, String s) {
            className = cn; methodName = mn; desc = d; params = p; returnType = rt; returnDesc = rd; classDesc = cd; throwsList = tl; source = s;
        }
        String displayName() {
            String c = className.contains(".") ? className.substring(className.lastIndexOf('.') + 1) : className;
            String p = Arrays.stream(params).map(a -> a.type + " " + a.name).collect(Collectors.joining(", "));
            return c + "." + methodName + "(" + p + ")";
        }
        String signature() {
            String p = Arrays.stream(params).map(a -> a.type + " " + a.name).collect(Collectors.joining(", "));
            return methodName + "(" + p + ")";
        }
        String shortName() {
            String p = Arrays.stream(params).map(a -> a.type).collect(Collectors.joining(", "));
            return methodName + "(" + p + ")";
        }
    }

    // ═══════════════════ Fields ═══════════════════

    final Path toolsDir, buildDir;
    final int port;
    final JavaCompiler compiler;
    final List<MethodInfo> methods = new CopyOnWriteArrayList<>();
    final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r); t.setDaemon(true); return t;
    });
    volatile String compileErr = "";

    // ═══════════════════ Entry ═══════════════════

    public static void main(String[] args) throws Exception {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) { System.err.println("ERROR: JDK (javac) not found"); System.exit(1); }
        new JavaToolBox(args.length > 0 ? args[0] : "./tools",
                args.length > 1 ? Integer.parseInt(args[1]) : 8080, jc).boot();
    }

    JavaToolBox(String dir, int port, JavaCompiler jc) {
        toolsDir = Paths.get(dir).toAbsolutePath();
        buildDir = toolsDir.resolve(".build");
        this.port = port; this.compiler = jc;
    }

    void boot() throws Exception {
        Files.createDirectories(buildDir);
        writeMethodRunner();
        rebuild();
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/", x -> send(x, 200, "text/html;charset=UTF-8", PAGE));
        s.createContext("/api/methods", this::apiMethods);
        s.createContext("/api/run", this::apiRun);
        s.createContext("/api/refresh", this::apiRefresh);
        s.createContext("/api/source", this::apiSource);
        s.setExecutor(pool);
        s.start();
        System.out.println("\n  * JavaToolBox\n  |- http://localhost:" + port
                + "\n  |- " + toolsDir + "\n  \\- " + methods.size() + " methods\n");
        startWatcher();
    }

    // ═══════════════════ MethodRunner ═══════════════════

    static final String RUNNER_SRC = """
import java.lang.reflect.*;
import java.util.*;
public class MethodRunner {
    public static void main(String[] args) throws Throwable {
        String cls = args[0], mtd = args[1];
        String[] margs = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : new String[0];
        Class<?> c = Class.forName(cls);
        Method target = null;
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(mtd)) continue;
            if (!Modifier.isPublic(m.getModifiers()) || !Modifier.isStatic(m.getModifiers())) continue;
            Class<?>[] t = m.getParameterTypes();
            if (t.length == margs.length || (t.length > 0 && t[t.length-1] == String[].class && margs.length >= t.length-1)) {
                target = m; break;
            }
        }
        if (target == null) { System.err.println("Method not found: " + cls + "." + mtd + " (" + margs.length + " args)"); System.exit(1); }
        Object[] conv = new Object[target.getParameterTypes().length];
        Class<?>[] types = target.getParameterTypes();
        for (int i = 0; i < conv.length; i++) {
            if (i >= margs.length) { conv[i] = types[i] == String[].class ? new String[0] : null; continue; }
            Class<?> t = types[i];
            if (t == String.class) conv[i] = margs[i];
            else if (t == int.class || t == Integer.class) conv[i] = Integer.parseInt(margs[i]);
            else if (t == long.class || t == Long.class) conv[i] = Long.parseLong(margs[i]);
            else if (t == double.class || t == Double.class) conv[i] = Double.parseDouble(margs[i]);
            else if (t == float.class || t == Float.class) conv[i] = Float.parseFloat(margs[i]);
            else if (t == boolean.class || t == Boolean.class) conv[i] = Boolean.parseBoolean(margs[i]);
            else if (t == byte.class || t == Byte.class) conv[i] = Byte.parseByte(margs[i]);
            else if (t == short.class || t == Short.class) conv[i] = Short.parseShort(margs[i]);
            else if (t == char.class || t == Character.class) conv[i] = margs[i].charAt(0);
            else if (t == String[].class) conv[i] = Arrays.copyOfRange(margs, i, margs.length);
            else conv[i] = margs[i];
        }
        try { Object r = target.invoke(null, conv); if (r != null) System.out.println(r); }
        catch (InvocationTargetException e) { throw e.getCause() != null ? e.getCause() : e; }
    }
}
""";

    void writeMethodRunner() throws IOException {
        Files.writeString(buildDir.resolve("MethodRunner.java"), RUNNER_SRC, StandardCharsets.UTF_8);
    }

    // ═══════════════════ Build ═══════════════════

    synchronized void rebuild() {
        compileErr = "";
        List<Path> files = scan();
        Path runner = buildDir.resolve("MethodRunner.java");
        if (Files.exists(runner)) { var a = new ArrayList<>(files); a.add(runner); files = a; }
        if (files.isEmpty()) { methods.clear(); return; }
        boolean ok = compile(files);
        methods.clear();
        if (ok) scanMethods();
    }

    List<Path> scan() {
        try (var s = Files.walk(toolsDir)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(".build")).sorted().collect(Collectors.toList());
        } catch (IOException e) { return List.of(); }
    }

    boolean compile(List<Path> files) {
        var dc = new DiagnosticCollector<JavaFileObject>();
        var fm = compiler.getStandardFileManager(dc, null, null);
        var opts = List.of("-d", buildDir.toString(), "-cp", cp(), "-encoding", "UTF-8", "-parameters");
        boolean ok = compiler.getTask(null, fm, dc, opts, null, fm.getJavaFileObjectsFromPaths(files)).call();
        close(fm);
        if (!ok) {
            var sb = new StringBuilder();
            dc.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .forEach(d -> sb.append(String.format("  %s:%d %s%n",
                            d.getSource() != null ? Path.of(d.getSource().getName()).getFileName() : "?",
                            d.getLineNumber(), d.getMessage(null))));
            compileErr = sb.toString();
        }
        return ok;
    }

    String cp() {
        var paths = new ArrayList<String>();
        paths.add(buildDir.toString());
        var lib = toolsDir.resolve("lib");
        if (Files.isDirectory(lib)) {
            try (var s = Files.list(lib)) {
                s.filter(p -> p.toString().endsWith(".jar"))
                        .forEach(j -> paths.add(j.toAbsolutePath().toString()));
            } catch (IOException ignored) {}
        }
        return String.join(File.pathSeparator, paths);
    }

    // ═══════════════════ Method Scanning + Javadoc ═══════════════════

    static final Pattern METHOD_RE = Pattern.compile(
            "public\\s+static\\s+([\\w.\\[\\]<> ,?]+)\\s+(\\w+)\\s*\\(([^)]*)\\)");

    void scanMethods() {
        var found = new ArrayList<MethodInfo>();
        try (var stream = Files.walk(toolsDir)) {
            var sources = stream.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(".build")).sorted().collect(Collectors.toList());
            for (Path src : sources) {
                String content = read(src);
                if (content.isEmpty()) continue;
                String pkg = rx(content, "package\\s+([\\w.]+)\\s*;");
                String base = src.getFileName().toString().replace(".java", "");
                String fullCls = pkg.isEmpty() ? base : pkg + "." + base;

                // Class-level Javadoc
                String classDesc = ParsedJavadoc.parse(extractClassDoc(content)).description;

                // Methods
                Matcher mm = METHOD_RE.matcher(content);
                while (mm.find()) {
                    String retType = mm.group(1).trim();
                    String mtdName = mm.group(2);
                    ParamInfo[] params = parseParamDecl(mm.group(3).trim());

                    // Method-level Javadoc with @param, @return, @throws
                    ParsedJavadoc pj = ParsedJavadoc.parse(extractRawDoc(content, mm.start()));

                    // Merge @param descriptions into parameter list
                    ParamInfo[] enriched = new ParamInfo[params.length];
                    for (int i = 0; i < params.length; i++) {
                        enriched[i] = new ParamInfo(params[i].name, params[i].type,
                                pj.params.getOrDefault(params[i].name, ""));
                    }

                    String rel = toolsDir.relativize(src).toString().replace('\\', '/');
                    found.add(new MethodInfo(fullCls, mtdName, pj.description, enriched,
                            retType, pj.returnDesc, classDesc, pj.throwsList, rel));
                }
            }
        } catch (IOException ignored) {}
        methods.addAll(found);
    }

    ParamInfo[] parseParamDecl(String s) {
        if (s.isEmpty()) return new ParamInfo[0];
        String[] parts = splitParams(s);
        var result = new ParamInfo[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i].trim().replace("...", "[]");
            int sp = p.lastIndexOf(' ');
            if (sp > 0) result[i] = new ParamInfo(p.substring(sp + 1).trim(), p.substring(0, sp).trim());
            else result[i] = new ParamInfo("arg" + i, p);
        }
        return result;
    }

    String[] splitParams(String s) {
        var r = new ArrayList<String>();
        int depth = 0;
        var cur = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '<') depth++;
            else if (c == '>') depth--;
            else if (c == ',' && depth == 0) { r.add(cur.toString().trim()); cur.setLength(0); continue; }
            cur.append(c);
        }
        if (cur.length() > 0) r.add(cur.toString().trim());
        return r.toArray(new String[0]);
    }

    /** Extract raw doc text between and before a given position */
    String extractRawDoc(String source, int pos) {
        String before = source.substring(0, pos).trim();
        int endDoc = before.lastIndexOf("*/");
        if (endDoc < 0) return "";
        int startDoc = before.lastIndexOf("/**", endDoc);
        if (startDoc < 0) return "";
        String between = before.substring(endDoc + 2);
        for (String line : between.split("\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("@")) continue;
            return "";
        }
        return before.substring(startDoc + 3, endDoc);
    }

    /** Extract class-level Javadoc (the ... immediately before the class declaration) */
    String extractClassDoc(String source) {
        Matcher cm = Pattern.compile(
                "\\bpublic\\s+(?:abstract\\s+)?(?:final\\s+)?(?:class|interface|enum)\\s+\\w+").matcher(source);
        if (!cm.find()) return "";
        String before = source.substring(0, cm.start()).trim();
        int endDoc = before.lastIndexOf("*/");
        if (endDoc < 0) return "";
        int startDoc = before.lastIndexOf("/**", endDoc);
        if (startDoc < 0) return "";
        String between = before.substring(endDoc + 2);
        for (String line : between.split("\\n")) {
            String l = line.trim();
            if (l.isEmpty() || l.startsWith("@")) continue;
            return "";
        }
        return before.substring(startDoc + 3, endDoc);
    }

    // ═══════════════════ Execution ═══════════════════

    String execute(String cls, String mtd, String[] args) throws Exception {
        String java = ProcessHandle.current().info().command().orElse("java");
        var cmd = new ArrayList<>(List.of(java, "-Dfile.encoding=UTF-8", "-cp", cp(), "MethodRunner", cls, mtd));
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).directory(toolsDir.toFile()).start();
        long t0 = System.currentTimeMillis();
        var of = pool.submit(() -> slurp(p.getInputStream()));
        var ef = pool.submit(() -> slurp(p.getErrorStream()));
        boolean done = p.waitFor(30, TimeUnit.SECONDS);
        String out = of.get(5, TimeUnit.SECONDS);
        String err = ef.get(5, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); err += "\n[TIMEOUT 30s]"; }
        return jsonResult(out, err, System.currentTimeMillis() - t0, done ? p.exitValue() : -1);
    }

    // ═══════════════════ HTTP API ═══════════════════

    void apiMethods(HttpExchange x) throws IOException {
        var sb = new StringBuilder("[");
        boolean first = true;
        for (var m : methods) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"className\":\"").append(je(m.className))
                    .append("\",\"methodName\":\"").append(je(m.methodName))
                    .append("\",\"displayName\":\"").append(je(m.displayName()))
                    .append("\",\"shortName\":\"").append(je(m.shortName()))
                    .append("\",\"signature\":\"").append(je(m.signature()))
                    .append("\",\"description\":\"").append(je(m.desc))
                    .append("\",\"returnType\":\"").append(je(m.returnType))
                    .append("\",\"returnDesc\":\"").append(je(m.returnDesc))
                    .append("\",\"classDesc\":\"").append(je(m.classDesc))
                    .append("\",\"source\":\"").append(je(m.source))
                    .append("\",\"throwsList\":[");
            for (int i = 0; i < m.throwsList.size(); i++) {
                if (i > 0) sb.append(",");
                var t = m.throwsList.get(i);
                sb.append("{\"exception\":\"").append(je(t.exception))
                        .append("\",\"desc\":\"").append(je(t.desc)).append("\"}");
            }
            sb.append("],\"params\":[");
            for (int i = 0; i < m.params.length; i++) {
                if (i > 0) sb.append(",");
                sb.append("{\"name\":\"").append(je(m.params[i].name))
                        .append("\",\"type\":\"").append(je(m.params[i].type))
                        .append("\",\"desc\":\"").append(je(m.params[i].desc)).append("\"}");
            }
            sb.append("]}");
        }
        sb.append("]");
        send(x, 200, "application/json", sb.toString());
    }

    void apiRun(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) { send(x, 405, "text/plain", "POST only"); return; }
        String body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        String cls = jv(body, "className"), mtd = jv(body, "methodName");
        if (cls.isEmpty() || mtd.isEmpty()) { send(x, 400, "application/json", "{\"error\":\"className and methodName required\"}"); return; }
        String[] args = parseArgs(body);
        try { send(x, 200, "application/json", execute(cls, mtd, args)); }
        catch (Exception e) { send(x, 200, "application/json", jsonResult("", e.getMessage(), 0, -1)); }
    }

    void apiRefresh(HttpExchange x) throws IOException {
        rebuild();
        send(x, 200, "application/json", "{\"count\":" + methods.size() + ",\"error\":\"" + je(compileErr) + "\"}");
    }

    void apiSource(HttpExchange x) throws IOException {
        String cls = qp(x.getRequestURI().getQuery(), "class");
        var m = methods.stream().filter(mi -> mi.className.equals(cls)).findFirst();
        if (m.isEmpty()) { send(x, 404, "text/plain", "Not found"); return; }
        send(x, 200, "text/plain;charset=UTF-8", read(toolsDir.resolve(m.get().source)));
    }

    // ═══════════════════ Utilities ═══════════════════

    void startWatcher() {
        var t = new Thread(() -> { try {
            var ws = FileSystems.getDefault().newWatchService();
            toolsDir.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            while (true) {
                var k = ws.take();
                boolean dirty = k.pollEvents().stream().anyMatch(e -> e.context().toString().endsWith(".java"));
                k.reset();
                if (dirty) { Thread.sleep(600); rebuild(); System.out.println("[watch] updated: " + methods.size() + " methods"); }
            }
        } catch (Exception ignored) {} }, "watcher");
        t.setDaemon(true); t.start();
    }

    String read(Path p) { try { return Files.readString(p, StandardCharsets.UTF_8); } catch (IOException e) { return ""; } }
    String slurp(InputStream is) { try (var r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) { return r.lines().collect(Collectors.joining("\n")); } catch (IOException e) { return ""; } }
    String rx(String s, String p) { var m = Pattern.compile(p).matcher(s); return m.find() ? m.group(1) : ""; }
    String[] parseArgs(String body) { var m = Pattern.compile("\"args\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(body); if (m.find()) { var l = new ArrayList<String>(); var am = Pattern.compile("\"([^\"]*)\"").matcher(m.group(1)); while (am.find()) l.add(am.group(1)); return l.toArray(new String[0]); } return new String[0]; }
    String je(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", ""); }
    String jsonResult(String o, String e, long ms, int c) { return "{\"stdout\":\"" + je(o) + "\",\"stderr\":\"" + je(e) + "\",\"ms\":" + ms + ",\"exitCode\":" + c + "}"; }
    String jv(String j, String k) { var m = Pattern.compile("\"" + k + "\"\\s*:\\s*\"([^\"]*)\"").matcher(j); return m.find() ? m.group(1) : ""; }
    String qp(String q, String k) { if (q == null) return ""; for (var p : q.split("&")) { var kv = p.split("=", 2); if (kv.length == 2 && kv[0].equals(k)) try { return URLDecoder.decode(kv[1], "UTF-8"); } catch (Exception ignored) {} } return ""; }
    void send(HttpExchange x, int c, String ct, String body) throws IOException { var b = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type", ct); x.getResponseHeaders().set("Access-Control-Allow-Origin", "*"); x.sendResponseHeaders(c, b.length); try (var os = x.getResponseBody()) { os.write(b); } }
    void close(Closeable c) { try { c.close(); } catch (IOException ignored) {} }

    // ═══════════════════ Web UI ═══════════════════

    static final String PAGE = """
<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>JavaToolBox</title>
<link rel="preconnect" href="https://fonts.googleapis.com">
<link href="https://fonts.googleapis.com/css2?family=JetBrains+Mono:wght@300;400;500;600;700&display=swap" rel="stylesheet">
<style>
:root{--bg:#09090b;--s1:#111114;--s2:#1a1a1e;--s3:#222228;--bd:#2a2a30;--ac:#d4a84b;--ac2:#b8922e;--tx:#c8c4bc;--tx2:#777;--ok:#4ade80;--er:#ef4444;--blue:#60a5fa}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'JetBrains Mono','Fira Code',Consolas,monospace;font-size:13px;color:var(--tx);background:var(--bg);display:flex;height:100vh;overflow:hidden}
aside{width:310px;min-width:310px;background:var(--s1);border-right:1px solid var(--bd);display:flex;flex-direction:column}
.hdr{padding:16px 18px;border-bottom:1px solid var(--bd);display:flex;align-items:center;gap:10px}
.hdr .ico{color:var(--ac);font-size:15px}
.hdr .ttl{flex:1;font-size:12px;font-weight:600;letter-spacing:1px}
.hdr button{background:none;border:1px solid var(--bd);color:var(--tx2);padding:4px 10px;font:10px inherit;border-radius:4px;cursor:pointer;transition:all .2s}
.hdr button:hover{border-color:var(--ac);color:var(--ac)}
.search{padding:8px 14px;border-bottom:1px solid var(--bd)}
.search input{width:100%;background:var(--s2);border:1px solid var(--bd);color:var(--tx);padding:6px 10px;font:11px inherit;border-radius:4px;outline:none;transition:border-color .2s}
.search input:focus{border-color:var(--ac)}
.search input::placeholder{color:var(--tx2);opacity:.4}
#list{flex:1;overflow-y:auto;padding:4px 0}
.grp-hdr{padding:7px 16px;font-size:11px;font-weight:600;color:var(--tx2);cursor:pointer;display:flex;align-items:center;gap:6px;transition:color .15s}
.grp-hdr:hover{color:var(--tx)}
.grp-hdr .arrow{font-size:9px;width:12px;display:inline-block;transition:transform .2s}
.grp-hdr .badge{margin-left:auto;background:var(--s3);color:var(--tx2);font-size:9px;padding:1px 6px;border-radius:8px;font-weight:400}
.grp-desc{padding:0 16px 4px 32px;font-size:10px;color:var(--tx2);opacity:.45;line-height:1.4;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.it{padding:6px 16px 6px 32px;cursor:pointer;font-size:11px;color:var(--tx2);transition:all .12s;border-left:2px solid transparent;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
.it:hover{background:var(--s2);color:var(--tx)}
.it.on{background:var(--s2);border-left-color:var(--ac);color:var(--ac)}
.it .mt{color:var(--tx)}
.ft{padding:8px 18px;border-top:1px solid var(--bd);font-size:10px;color:var(--tx2)}
main{flex:1;display:flex;flex-direction:column;overflow:hidden}
.emp{flex:1;display:flex;align-items:center;justify-content:center;color:var(--tx2);font-size:13px}
.pnl{flex:1;display:none;flex-direction:column;overflow-y:auto}
.pnl.on{display:flex;animation:fi .2s ease}
@keyframes fi{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
.ph{padding:28px 36px 18px;border-bottom:1px solid var(--bd)}
.ph .ci{font-size:11px;color:var(--tx2);margin-bottom:10px;line-height:1.5}
.ph .ci .cn{color:var(--ac);font-weight:500}
.ph .ci .cd{opacity:.6}
.ph h2{font-size:16px;font-weight:600;margin-bottom:6px;word-break:break-all}
.ph h2 .rt{color:var(--blue);font-weight:400;font-size:12px}
.ph .doc{font-size:12px;color:var(--tx2);line-height:1.7;margin-top:4px}
.ph .ri{font-size:11px;color:var(--tx2);margin-top:5px}
.ph .ri .rl{opacity:.5;margin-right:4px}
.ph .ti-wrap{font-size:11px;color:var(--tx2);margin-top:4px}
.ph .ti-wrap .tl{opacity:.5;margin-right:4px}
.ph .ti-wrap .ti{display:inline-block;background:rgba(239,68,68,.08);color:var(--er);padding:1px 6px;border-radius:3px;margin-right:5px;font-size:10px;cursor:help}
.ph .src{font-size:10px;color:var(--tx2);opacity:.3;margin-top:6px}
.pp{padding:18px 36px 0;display:flex;flex-direction:column;gap:12px}
.pp .empty{color:var(--tx2);font-size:11px;font-style:italic}
.pr{display:flex;flex-direction:column;gap:3px}
.pr label{font-size:10px;color:var(--tx2);display:flex;align-items:center;gap:6px}
.pr label .pn{color:var(--tx);font-weight:500}
.pr label .pt2{color:var(--blue);font-size:9px;background:rgba(96,165,250,.08);padding:1px 5px;border-radius:3px}
.pr input{width:100%;background:var(--s1);border:1px solid var(--bd);color:var(--tx);padding:7px 10px;font:12px inherit;border-radius:4px;outline:none;transition:border-color .2s}
.pr input:focus{border-color:var(--ac)}
.pr input::placeholder{color:var(--tx2);opacity:.3}
.pr .pd{font-size:10px;color:var(--tx2);line-height:1.4;margin-top:2px;padding-left:2px;opacity:.6}
.act{padding:14px 36px;display:flex;gap:8px;align-items:center}
.btn{background:none;border:1px solid var(--bd);color:var(--tx);padding:7px 18px;font:11px inherit;border-radius:5px;cursor:pointer;transition:all .2s}
.btn:hover{border-color:var(--s3);background:var(--s2)}
.btn.run{background:var(--ac);border-color:var(--ac);color:#000;font-weight:600}
.btn.run:hover{background:var(--ac2);border-color:var(--ac2)}
.btn.run:disabled{opacity:.35;cursor:wait}
.hint{font-size:10px;color:var(--tx2);opacity:.35}
#res{flex:1;margin:8px 36px 28px;border:1px solid var(--bd);border-radius:5px;display:none;flex-direction:column;min-height:0;max-height:50vh}
#res.on{display:flex}
.rb{padding:7px 12px;background:var(--s1);border-bottom:1px solid var(--bd);display:flex;align-items:center;justify-content:space-between;font-size:11px;border-radius:5px 5px 0 0}
.tabs{display:flex;background:var(--s1);border-bottom:1px solid var(--bd)}
.tab{padding:6px 14px;font-size:11px;cursor:pointer;color:var(--tx2);border-bottom:2px solid transparent;transition:all .12s}
.tab:hover{color:var(--tx)}
.tab.on{color:var(--ac);border-bottom-color:var(--ac)}
.out{flex:1;overflow:auto;padding:12px 14px;background:#050507;border-radius:0 0 5px 5px}
.out pre{font:11px/1.7 inherit;white-space:pre-wrap;word-break:break-all}
.out pre.so{color:var(--tx)}
.out pre.se{color:var(--er)}
.ov{position:fixed;inset:0;background:rgba(0,0,0,.65);backdrop-filter:blur(4px);display:none;align-items:center;justify-content:center;z-index:99}
.ov.on{display:flex}
.mdl{width:72vw;max-width:860px;max-height:80vh;background:var(--s1);border:1px solid var(--bd);border-radius:8px;display:flex;flex-direction:column;overflow:hidden;animation:fi .2s ease}
.mh{padding:10px 14px;border-bottom:1px solid var(--bd);display:flex;align-items:center;justify-content:space-between;font-size:12px;color:var(--tx2)}
.mh button{background:none;border:none;color:var(--tx2);font-size:15px;cursor:pointer;padding:4px}
.mh button:hover{color:var(--tx)}
.mb{flex:1;overflow:auto;padding:14px}
.mb pre{font:11px/1.6 inherit;color:var(--tx);white-space:pre}
::-webkit-scrollbar{width:5px;height:5px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--bd);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:#3a3a42}
.sp{display:inline-block;width:11px;height:11px;border:2px solid var(--bd);border-top-color:var(--ac);border-radius:50%;animation:rn .5s linear infinite;vertical-align:middle;margin-right:5px}
@keyframes rn{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<aside>
<div class="hdr"><span class="ico">*</span><span class="ttl">JavaToolBox</span><button id="btnRefresh">Refresh</button></div>
<div class="search"><input id="search" type="text" placeholder="Search methods..."></div>
<div id="list"></div>
<div class="ft" id="ft"></div>
</aside>
<main>
<div class="emp" id="emp">&larr; Select a method</div>
<div class="pnl" id="pnl">
<div class="ph">
<div class="ci" id="tc"></div>
<h2 id="tn"></h2>
<div class="doc" id="td"></div>
<div class="ri" id="tr"></div>
<div class="ti-wrap" id="th"></div>
<div class="src" id="ts"></div>
</div>
<div class="pp" id="pp"></div>
<div class="act"><button class="btn run" id="rb">Run</button><button class="btn" id="btnSrc">Source</button><span class="hint">Ctrl+Enter</span></div>
<div id="res">
<div class="rb"><span id="rs"></span><span id="rtx" style="color:var(--tx2)"></span></div>
<div class="tabs"><div class="tab on" data-t="stdout">stdout</div><div class="tab" data-t="stderr">stderr</div></div>
<div class="out"><pre id="po" class="so"></pre><pre id="pe" class="se" style="display:none"></pre></div>
</div>
</div>
</main>
<div class="ov" id="ov"><div class="mdl"><div class="mh"><span id="mt"></span><button id="btnCloseSrc">&#x2715;</button></div><div class="mb"><pre id="mc"></pre></div></div></div>
<script>
var M = [];
var G = {};
var SEL = -1;
var EXP = {};

function init() {
    document.addEventListener("keydown", function(e) {
        if ((e.ctrlKey || e.metaKey) && e.key === "Enter") { e.preventDefault(); go(); }
    });
    document.getElementById("search").addEventListener("input", function() { render(); });
    document.getElementById("btnRefresh").addEventListener("click", function() { refresh(); });
    document.getElementById("rb").addEventListener("click", function() { go(); });
    document.getElementById("btnSrc").addEventListener("click", function() { vsrc(); });
    document.getElementById("btnCloseSrc").addEventListener("click", function() { hsrc(); });
    document.getElementById("ov").addEventListener("click", function(e) { if (e.target === this) hsrc(); });

    document.getElementById("list").addEventListener("click", function(e) {
        var hdr = e.target.closest(".grp-hdr");
        if (hdr) { tog(hdr.dataset.cls); return; }
        var it = e.target.closest(".it");
        if (it) { sel(parseInt(it.dataset.idx)); }
    });

    document.getElementById("pp").addEventListener("keydown", function(e) {
        if (e.key === "Enter" && !e.isComposing) go();
    });

    document.querySelectorAll(".tab").forEach(function(t) {
        t.addEventListener("click", function() { stab(this.dataset.t); });
    });

    refresh();
}

function refresh() {
    fetch("/api/methods").then(function(r) { return r.json(); }).then(function(data) {
        M = data;
    }).catch(function() {
        M = [];
    }).then(function() {
        SEL = -1;
        buildG();
        render();
        document.getElementById("emp").style.display = "flex";
        document.getElementById("pnl").classList.remove("on");
    });
}

function buildG() {
    G = {};
    M.forEach(function(m, i) {
        if (!G[m.className]) G[m.className] = [];
        G[m.className].push(i);
    });
    Object.keys(G).forEach(function(k) {
        if (EXP[k] === undefined) EXP[k] = true;
    });
}

function render() {
    var el = document.getElementById("list");
    var q = document.getElementById("search").value.toLowerCase();
    if (!M.length) {
        el.innerHTML = '<div style="padding:20px;color:var(--tx2);font-size:11px;text-align:center">No methods found<br><br><span style="opacity:.4">Place .java files in tools/</span></div>';
        document.getElementById("ft").textContent = "";
        return;
    }
    var html = "";
    var cnt = 0;
    for (var cls in G) {
        var idx = G[cls];
        var filt = q ? idx.filter(function(i) {
            var m = M[i];
            return (m.className + m.methodName + m.displayName + m.description).toLowerCase().indexOf(q) >= 0;
        }) : idx;
        if (!filt.length) continue;
        cnt += filt.length;
        var exp = EXP[cls] !== false;
        var classDesc = M[filt[0]].classDesc || "";
        html += '<div class="grp-hdr" data-cls="' + esc(cls) + '">';
        html += '<span class="arrow">' + (exp ? '&#9660;' : '&#9654;') + '</span>';
        html += esc(cls);
        html += '<span class="badge">' + filt.length + '</span>';
        html += '</div>';
        if (exp) {
            if (classDesc) {
                html += '<div class="grp-desc">' + esc(classDesc) + '</div>';
            }
            filt.forEach(function(i) {
                var m = M[i];
                html += '<div class="it' + (i === SEL ? ' on' : '') + '" data-idx="' + i + '">';
                html += '<span class="mt">' + esc(m.shortName) + '</span>';
                html += '</div>';
            });
        }
    }
    el.innerHTML = html || '<div style="padding:20px;color:var(--tx2);font-size:11px;text-align:center">No match</div>';
    document.getElementById("ft").textContent = cnt + " methods";
}

function tog(cls) {
    EXP[cls] = !EXP[cls];
    render();
}

function sel(i) {
    SEL = i;
    var m = M[i];
    document.getElementById("emp").style.display = "none";
    document.getElementById("pnl").classList.add("on");

    // Class info
    var ciHtml = '<span class="cn">' + esc(m.className) + '</span>';
    if (m.classDesc) ciHtml += ' <span class="cd">&mdash; ' + esc(m.classDesc) + '</span>';
    document.getElementById("tc").innerHTML = ciHtml;

    // Method signature + return type
    document.getElementById("tn").innerHTML = esc(m.signature) + ' <span class="rt">' + esc(m.returnType) + '</span>';

    // Method description
    document.getElementById("td").textContent = m.description || "No description";

    // Return info
    var tr = document.getElementById("tr");
    if (m.returnType !== "void") {
        var rHtml = '<span class="rl">Returns</span> ' + esc(m.returnType);
        if (m.returnDesc) rHtml += ' &mdash; ' + esc(m.returnDesc);
        tr.innerHTML = rHtml;
        tr.style.display = "";
    } else {
        tr.style.display = "none";
    }

    // Throws info
    var th = document.getElementById("th");
    if (m.throwsList && m.throwsList.length) {
        var thHtml = '<span class="tl">Throws</span> ';
        m.throwsList.forEach(function(t) {
            thHtml += '<span class="ti" title="' + esc(t.desc) + '">' + esc(t.exception) + '</span>';
        });
        th.innerHTML = thHtml;
        th.style.display = "";
    } else {
        th.style.display = "none";
    }

    // Source
    document.getElementById("ts").textContent = m.source;

    // Parameters with descriptions
    var pp = document.getElementById("pp");
    if (!m.params.length) {
        pp.innerHTML = '<div class="empty">No parameters</div>';
    } else {
        var h = "";
        m.params.forEach(function(p, idx) {
            var ph = esc(p.type) + " value" + (p.type.indexOf("[]") >= 0 ? " (space-separated)" : "");
            h += '<div class="pr">';
            h += '<label><span class="pn">' + esc(p.name) + '</span><span class="pt2">' + esc(p.type) + '</span></label>';
            h += '<input id="p' + idx + '" placeholder="' + ph + '">';
            if (p.desc) h += '<div class="pd">' + esc(p.desc) + '</div>';
            h += '</div>';
        });
        pp.innerHTML = h;
    }

    document.getElementById("res").classList.remove("on");
    render();
    var f = pp.querySelector("input");
    if (f) f.focus();
}

function go() {
    if (SEL < 0) return;
    var m = M[SEL];
    var btn = document.getElementById("rb");
    var args = [];
    m.params.forEach(function(p, idx) {
        var el = document.getElementById("p" + idx);
        if (el) {
            var v = el.value.trim();
            if (v) {
                if (p.type.indexOf("[]") >= 0) {
                    v.split(/\s+/).forEach(function(a) { args.push(a); });
                } else {
                    args.push(v);
                }
            }
        }
    });
    btn.disabled = true;
    btn.innerHTML = '<span class="sp"></span>Running';
    document.getElementById("res").classList.add("on");
    document.getElementById("rs").innerHTML = '<span class="sp"></span>';
    document.getElementById("rtx").textContent = "";
    document.getElementById("po").textContent = "";
    document.getElementById("pe").textContent = "";
    stab("stdout");
    fetch("/api/run", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body: JSON.stringify({className: m.className, methodName: m.methodName, args: args})
    }).then(function(r) { return r.json(); }).then(function(r) {
        var ok = r.exitCode === 0 && !r.stderr;
        document.getElementById("rs").innerHTML = ok
            ? '<span style="color:var(--ok)">OK</span>'
            : '<span style="color:var(--er)">Exit ' + r.exitCode + '</span>';
        document.getElementById("rtx").textContent = r.ms + "ms";
        document.getElementById("po").textContent = r.stdout || "(no output)";
        document.getElementById("pe").textContent = r.stderr || "(no errors)";
        if (!ok && r.stderr) stab("stderr");
    }).catch(function(e) {
        document.getElementById("rs").innerHTML = '<span style="color:var(--er)">Error</span>';
        document.getElementById("po").textContent = e.message;
    }).then(function() {
        btn.disabled = false;
        btn.innerHTML = "Run";
    });
}

function vsrc() {
    if (SEL < 0) return;
    var m = M[SEL];
    fetch("/api/source?class=" + encodeURIComponent(m.className)).then(function(r) { return r.text(); }).then(function(c) {
        document.getElementById("mt").textContent = m.source;
        document.getElementById("mc").textContent = c;
        document.getElementById("ov").classList.add("on");
    }).catch(function() { alert("Failed to load source"); });
}

function hsrc() {
    document.getElementById("ov").classList.remove("on");
}

function stab(n) {
    document.querySelectorAll(".tab").forEach(function(t) {
        t.classList.toggle("on", t.dataset.t === n);
    });
    document.getElementById("po").style.display = n === "stdout" ? "block" : "none";
    document.getElementById("pe").style.display = n === "stderr" ? "block" : "none";
}

function esc(s) {
    var d = document.createElement("div");
    d.textContent = s;
    return d.innerHTML;
}

init();
</script>
</body>
</html>
""";
}
