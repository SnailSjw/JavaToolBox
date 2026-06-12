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
            raw = raw.replaceAll("\\{@(?:code|link|literal|value)[^}]*}", "");
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

    final Path toolsDir, buildDir, webDir;
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
        webDir = Path.of(System.getProperty("user.dir")).resolve("web").toAbsolutePath();
        this.port = port;
        this.compiler = jc;
    }

    void boot() throws Exception {
        Files.createDirectories(buildDir);
        writeMethodRunner();
        rebuild();
        HttpServer s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/", this::servePage);
        s.createContext("/api/methods", this::apiMethods);
        s.createContext("/api/run", this::apiRun);
        s.createContext("/api/refresh", this::apiRefresh);
        s.createContext("/api/source", this::apiSource);
        s.setExecutor(pool);
        s.start();
        System.out.println("\n  * JavaToolBox\n  |- http://localhost:" + port
                + "\n  |- tools: " + toolsDir
                + "\n  |- web:   " + webDir
                + "\n  \\- " + methods.size() + " methods\n");
        startWatcher();
    }

    // ═══════════════════ Page Serving ═══════════════════

    void servePage(HttpExchange x) throws IOException {
        if (!"/".equals(x.getRequestURI().getPath())) { send(x, 404, "text/plain", "Not found"); return; }
        Path indexFile = webDir.resolve("index.html");
        if (!Files.exists(indexFile)) {
            send(x, 500, "text/plain", "index.html not found at: " + indexFile);
            return;
        }
        String html = Files.readString(indexFile, StandardCharsets.UTF_8);
        send(x, 200, "text/html;charset=UTF-8", html);
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
                String classDesc = ParsedJavadoc.parse(extractClassDoc(content)).description;
                Matcher mm = METHOD_RE.matcher(content);
                while (mm.find()) {
                    String retType = mm.group(1).trim();
                    String mtdName = mm.group(2);
                    ParamInfo[] params = parseParamDecl(mm.group(3).trim());
                    ParsedJavadoc pj = ParsedJavadoc.parse(extractRawDoc(content, mm.start()));
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
}
