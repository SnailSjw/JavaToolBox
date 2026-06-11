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

/**
 * JavaToolBox — 零散 Java 工具类的 Web 调用平台
 *
 * 用法:  javac JavaToolBox.java && java JavaToolBox [工具目录] [端口]
 * 默认:  扫描 ./tools 目录, 监听 8080 端口
 * 要求:  JDK 17+
 */
public class JavaToolBox {

    // ═══════════════════ 数据结构 ═══════════════════

    static class Tool {
        final String qname, name, desc, src;
        Tool(String q, String n, String d, String s) { qname=q; name=n; desc=d; src=s; }
    }

    // ═══════════════════ 字段 ═══════════════════

    final Path toolsDir, buildDir;
    final int port;
    final JavaCompiler compiler;
    final List<Tool> tools = new CopyOnWriteArrayList<>();
    final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r); t.setDaemon(true); return t;
    });
    volatile String compileErr = "";

    // ═══════════════════ 入口 ═══════════════════

    public static void main(String[] args) throws Exception {
        var jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) { System.err.println("需要 JDK (javac)"); System.exit(1); }
        new JavaToolBox(
            args.length > 0 ? args[0] : "./tools",
            args.length > 1 ? Integer.parseInt(args[1]) : 8080, jc
        ).boot();
    }

    JavaToolBox(String dir, int port, JavaCompiler jc) {
        toolsDir = Paths.get(dir).toAbsolutePath();
        buildDir = toolsDir.resolve(".build");
        this.port = port;
        compiler = jc;
    }

    void boot() throws Exception {
        Files.createDirectories(buildDir);
        rebuild();
        var s = HttpServer.create(new InetSocketAddress(port), 0);
        s.createContext("/",            x -> send(x, 200, "text/html;charset=UTF-8", PAGE));
        s.createContext("/api/tools",   this::apiTools);
        s.createContext("/api/run",     this::apiRun);
        s.createContext("/api/refresh", this::apiRefresh);
        s.createContext("/api/source",  this::apiSource);
        s.setExecutor(pool);
        s.start();
        System.out.println("\n  ◆ JavaToolBox\n  ├─ http://localhost:" + port
            + "\n  ├─ " + toolsDir + "\n  └─ " + tools.size() + " 个工具\n");
        startWatcher();
    }

    // ═══════════════════ 核心: 扫描 / 编译 / 执行 ═══════════════════

    synchronized void rebuild() {
        compileErr = "";
        var files = scan();
        if (files.isEmpty()) { tools.clear(); return; }
        var ok = compile(files);
        var list = new ArrayList<Tool>();
        for (var f : files) {
            var c = read(f);
            if (!c.contains("public static void main(String")) continue;
            var pkg = rx(c, "package\\s+([\\w.]+)\\s*;");
            var base = f.getFileName().toString().replace(".java", "");
            list.add(new Tool(pkg.isEmpty() ? base : pkg+"."+base, base, javadoc(c),
                toolsDir.relativize(f).toString().replace('\\','/')));
        }
        tools.clear();
        tools.addAll(list);
    }

    List<Path> scan() {
        try (var s = Files.walk(toolsDir)) {
            return s.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains(".build"))
                    .sorted().collect(Collectors.toList());
        } catch (IOException e) { return List.of(); }
    }

    boolean compile(List<Path> files) {
        var dc = new DiagnosticCollector<JavaFileObject>();
        var fm = compiler.getStandardFileManager(dc, null, null);
        var opts = List.of("-d", buildDir.toString(), "-cp", cp(), "-encoding", "UTF-8");
        var ok = compiler.getTask(null, fm, dc, opts, null, fm.getJavaFileObjectsFromPaths(files)).call();
        close(fm);
        if (!ok) {
            var sb = new StringBuilder();
            dc.getDiagnostics().stream().filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                .forEach(d -> sb.append(String.format("  %s:%d %s%n",
                    d.getSource()!=null ? Path.of(d.getSource().getName()).getFileName() : "?",
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


    String execute(String cls, String[] args) throws Exception {
        var java = ProcessHandle.current().info().command().orElse("java");
        var cmd = new ArrayList<>(List.of(java, "-Dfile.encoding=UTF-8", "-cp", cp(), cls));
        cmd.addAll(List.of(args));
        var pb = new ProcessBuilder(cmd).directory(toolsDir.toFile());
        var p = pb.start();
        var t0 = System.currentTimeMillis();
        var of = pool.submit(() -> slurp(p.getInputStream()));
        var ef = pool.submit(() -> slurp(p.getErrorStream()));
        var done = p.waitFor(30, TimeUnit.SECONDS);
        var out = of.get(5, TimeUnit.SECONDS);
        var err = ef.get(5, TimeUnit.SECONDS);
        if (!done) { p.destroyForcibly(); err += "\n[TIMEOUT 30s]"; }
        return rj(out, err, System.currentTimeMillis()-t0, done ? p.exitValue() : -1);
    }

    // ═══════════════════ HTTP API ═══════════════════

    void apiTools(HttpExchange x) throws IOException {
        send(x, 200, "application/json", tools.stream()
            .map(t -> "{\"className\":\""+q(t.qname)+"\",\"name\":\""+q(t.name)
                +"\",\"description\":\""+q(t.desc)+"\",\"source\":\""+q(t.src)+"\"}")
            .collect(Collectors.joining(",", "[", "]")));
    }

    void apiRun(HttpExchange x) throws IOException {
        if (!"POST".equals(x.getRequestMethod())) { send(x,405,"text/plain","POST only"); return; }
        var body = new String(x.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        var cls = jv(body, "className");
        if (cls.isEmpty()) { send(x,400,"application/json","{\"error\":\"className required\"}"); return; }
        try { send(x, 200, "application/json", execute(cls, pargs(body))); }
        catch (Exception e) { send(x, 200, "application/json", rj("", e.getMessage(), 0, -1)); }
    }

    void apiRefresh(HttpExchange x) throws IOException {
        rebuild();
        send(x, 200, "application/json", "{\"count\":"+tools.size()+",\"error\":\""+q(compileErr)+"\"}");
    }

    void apiSource(HttpExchange x) throws IOException {
        var cls = up(x.getRequestURI().getQuery(), "class");
        var t = tools.stream().filter(tool -> tool.name.equals(cls)||tool.qname.equals(cls)).findFirst();
        if (t.isEmpty()) { send(x,404,"text/plain","Not found"); return; }
        send(x, 200, "text/plain;charset=UTF-8", read(toolsDir.resolve(t.get().src)));
    }

    // ═══════════════════ 工具方法 ═══════════════════

    String[] pargs(String body) {
        var am = Pattern.compile("\"args\"\\s*:\\s*\\[([^\\]]*)\\]").matcher(body);
        if (am.find()) {
            var l = new ArrayList<String>();
            var m = Pattern.compile("\"([^\"]*)\"").matcher(am.group(1));
            while (m.find()) l.add(m.group(1));
            return l.toArray(new String[0]);
        }
        var sm = Pattern.compile("\"args\"\\s*:\\s*\"([^\"]*)\"").matcher(body);
        if (sm.find()) { var s = sm.group(1).trim(); return s.isEmpty() ? new String[0] : s.split("\\s+"); }
        return new String[0];
    }

    void startWatcher() {
        var t = new Thread(() -> { try {
            var ws = FileSystems.getDefault().newWatchService();
            toolsDir.register(ws, StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY, StandardWatchEventKinds.ENTRY_DELETE);
            while (true) {
                var k = ws.take();
                var dirty = k.pollEvents().stream().anyMatch(e -> e.context().toString().endsWith(".java"));
                k.reset();
                if (dirty) { Thread.sleep(600); rebuild(); System.out.println("[watch] updated: "+tools.size()); }
            }
        } catch (Exception ignored) {} }, "watcher");
        t.setDaemon(true); t.start();
    }

    String read(Path p) { try { return Files.readString(p, StandardCharsets.UTF_8); } catch (IOException e) { return ""; } }
    String slurp(InputStream is) { try (var r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) { return r.lines().collect(Collectors.joining("\n")); } catch (IOException e) { return ""; } }
    String rx(String s, String p) { var m = Pattern.compile(p).matcher(s); return m.find() ? m.group(1) : ""; }
    String javadoc(String s) { var m = Pattern.compile("/\\*\\*([\\s\\S]*?)\\*/").matcher(s); if (!m.find()) return ""; return Arrays.stream(m.group(1).split("\\n")).map(l -> l.replaceAll("^\\s*\\*?\\s?", "").trim()).filter(l -> !l.isEmpty() && !l.startsWith("@")).collect(Collectors.joining(" ")); }
    String q(String s) { return s==null?"":s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\r",""); }
    String rj(String o, String e, long ms, int c) { return "{\"stdout\":\""+q(o)+"\",\"stderr\":\""+q(e)+"\",\"ms\":"+ms+",\"exitCode\":"+c+"}"; }
    String jv(String j, String k) { var m = Pattern.compile("\""+k+"\"\\s*:\\s*\"([^\"]*)\"").matcher(j); return m.find()?m.group(1):""; }
    String up(String q, String k) { if (q==null) return ""; for (var p : q.split("&")) { var kv = p.split("=",2); if (kv.length==2&&kv[0].equals(k)) try { return URLDecoder.decode(kv[1],"UTF-8"); } catch(Exception e){} } return ""; }
    void send(HttpExchange x, int c, String ct, String body) throws IOException { var b = body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type",ct); x.getResponseHeaders().set("Access-Control-Allow-Origin","*"); x.sendResponseHeaders(c,b.length); try(var os=x.getResponseBody()){os.write(b);} }
    void close(Closeable c) { try { c.close(); } catch (IOException ignored) {} }

    // ═══════════════════ 嵌入式 Web UI ═══════════════════

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
:root{--bg:#09090b;--s1:#111114;--s2:#1a1a1e;--s3:#222228;--bd:#2a2a30;--ac:#d4a84b;--ac2:#b8922e;--tx:#c8c4bc;--tx2:#777;--ok:#4ade80;--er:#ef4444}
*{margin:0;padding:0;box-sizing:border-box}
body{font-family:'JetBrains Mono','Fira Code',Consolas,monospace;font-size:13px;color:var(--tx);background:var(--bg);display:flex;height:100vh;overflow:hidden}
aside{width:260px;min-width:260px;background:var(--s1);border-right:1px solid var(--bd);display:flex;flex-direction:column}
.hdr{padding:18px 20px;border-bottom:1px solid var(--bd);display:flex;align-items:center;gap:12px}
.hdr .ico{color:var(--ac);font-size:16px}
.hdr .ttl{flex:1;font-size:13px;font-weight:600;letter-spacing:1px}
.hdr button{background:none;border:1px solid var(--bd);color:var(--tx2);padding:5px 12px;font:11px inherit;border-radius:4px;cursor:pointer;transition:all .2s}
.hdr button:hover{border-color:var(--ac);color:var(--ac)}
#list{flex:1;overflow-y:auto;padding:6px 0}
.it{padding:9px 20px;cursor:pointer;display:flex;align-items:center;gap:10px;border-left:2px solid transparent;transition:all .12s;font-size:12px}
.it:hover{background:var(--s2)}
.it.on{background:var(--s2);border-left-color:var(--ac)}
.it .ic{color:var(--ac2);font-size:10px;opacity:.5}
.ft{padding:10px 20px;border-top:1px solid var(--bd);font-size:10px;color:var(--tx2)}
main{flex:1;display:flex;flex-direction:column;overflow:hidden}
.emp{flex:1;display:flex;align-items:center;justify-content:center;color:var(--tx2);font-size:13px}
.pnl{flex:1;display:none;flex-direction:column;overflow:hidden}
.pnl.on{display:flex;animation:fi .25s ease}
@keyframes fi{from{opacity:0;transform:translateY(6px)}to{opacity:1;transform:none}}
.ph{padding:28px 36px 22px;border-bottom:1px solid var(--bd)}
.ph h2{font-size:20px;font-weight:600;margin-bottom:8px}
.ph .doc{font-size:12px;color:var(--tx2);line-height:1.7}
.ph .src{font-size:10px;color:var(--tx2);opacity:.4;margin-top:6px}
.pp{padding:22px 36px 0}
.pp label{display:block;font-size:10px;color:var(--tx2);letter-spacing:1.5px;text-transform:uppercase;margin-bottom:8px}
#args{width:100%;background:var(--s1);border:1px solid var(--bd);color:var(--tx);padding:10px 14px;font:13px inherit;border-radius:6px;outline:none;transition:border-color .2s}
#args:focus{border-color:var(--ac)}
#args::placeholder{color:var(--tx2);opacity:.4}
.act{padding:16px 36px;display:flex;gap:10px}
.btn{background:none;border:1px solid var(--bd);color:var(--tx);padding:8px 22px;font:12px inherit;border-radius:6px;cursor:pointer;transition:all .2s}
.btn:hover{border-color:var(--s3);background:var(--s2)}
.btn.run{background:var(--ac);border-color:var(--ac);color:#000;font-weight:600}
.btn.run:hover{background:var(--ac2);border-color:var(--ac2)}
.btn.run:disabled{opacity:.35;cursor:wait}
#res{flex:1;margin:10px 36px 32px;border:1px solid var(--bd);border-radius:6px;display:none;flex-direction:column;min-height:0}
#res.on{display:flex}
.rb{padding:8px 14px;background:var(--s1);border-bottom:1px solid var(--bd);display:flex;align-items:center;justify-content:space-between;font-size:11px;border-radius:6px 6px 0 0}
.tabs{display:flex;background:var(--s1);border-bottom:1px solid var(--bd)}
.tab{padding:7px 16px;font-size:11px;cursor:pointer;color:var(--tx2);border-bottom:2px solid transparent;transition:all .12s}
.tab:hover{color:var(--tx)}
.tab.on{color:var(--ac);border-bottom-color:var(--ac)}
.out{flex:1;overflow:auto;padding:14px 16px;background:#050507;border-radius:0 0 6px 6px}
.out pre{font:12px/1.7 inherit;white-space:pre-wrap;word-break:break-all}
.out pre.so{color:var(--tx)}
.out pre.se{color:var(--er)}
.ov{position:fixed;inset:0;background:rgba(0,0,0,.65);backdrop-filter:blur(4px);display:none;align-items:center;justify-content:center;z-index:99}
.ov.on{display:flex}
.mdl{width:72vw;max-width:860px;max-height:80vh;background:var(--s1);border:1px solid var(--bd);border-radius:8px;display:flex;flex-direction:column;overflow:hidden;animation:fi .2s ease}
.mh{padding:12px 16px;border-bottom:1px solid var(--bd);display:flex;align-items:center;justify-content:space-between;font-size:12px;color:var(--tx2)}
.mh button{background:none;border:none;color:var(--tx2);font-size:16px;cursor:pointer;padding:4px}
.mh button:hover{color:var(--tx)}
.mb{flex:1;overflow:auto;padding:16px}
.mb pre{font:12px/1.6 inherit;color:var(--tx);white-space:pre}
::-webkit-scrollbar{width:5px;height:5px}
::-webkit-scrollbar-track{background:transparent}
::-webkit-scrollbar-thumb{background:var(--bd);border-radius:3px}
::-webkit-scrollbar-thumb:hover{background:#3a3a42}
.sp{display:inline-block;width:12px;height:12px;border:2px solid var(--bd);border-top-color:var(--ac);border-radius:50%;animation:rn .5s linear infinite;vertical-align:middle;margin-right:6px}
@keyframes rn{to{transform:rotate(360deg)}}
</style>
</head>
<body>
<aside>
<div class="hdr"><span class="ico">◆</span><span class="ttl">JavaToolBox</span><button onclick="refresh()">刷新</button></div>
<div id="list"></div>
<div class="ft" id="ft"></div>
</aside>
<main>
<div class="emp" id="emp">← 选择左侧工具开始</div>
<div class="pnl" id="pnl">
<div class="ph"><h2 id="tn"></h2><div class="doc" id="td"></div><div class="src" id="ts"></div></div>
<div class="pp"><label>命令行参数（空格分隔）</label><input id="args" placeholder="arg1 arg2 arg3 ..."></div>
<div class="act"><button class="btn run" id="rb" onclick="go()">▶ 运行</button><button class="btn" onclick="src()">查看源码</button></div>
<div id="res">
<div class="rb"><span id="rs"></span><span id="rt" style="color:var(--tx2)"></span></div>
<div class="tabs"><div class="tab on" data-t="stdout" onclick="tab('stdout')">stdout</div><div class="tab" data-t="stderr" onclick="tab('stderr')">stderr</div></div>
<div class="out"><pre id="po" class="so"></pre><pre id="pe" class="se" style="display:none"></pre></div>
</div>
</div>
</main>
<div class="ov" id="ov" onclick="if(event.target===this)hsrc()"><div class="mdl"><div class="mh"><span id="mt"></span><button onclick="hsrc()">✕</button></div><div class="mb"><pre id="mc"></pre></div></div></div>
<script>
let T=[],S=-1;
async function init(){document.getElementById('args').addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.isComposing)go()});await refresh()}
async function refresh(){
try{T=await(await fetch('/api/tools')).json()}catch(e){T=[]}
if(S>=T.length)S=-1;
if(S<0){document.getElementById('emp').style.display='flex';document.getElementById('pnl').classList.remove('on')}
render()}
function render(){
const el=document.getElementById('list');
if(!T.length){el.innerHTML='<div style="padding:24px 20px;color:var(--tx2);font-size:11px;text-align:center">未发现工具<br><br><span style="opacity:.5">将 .java 文件放入 tools/ 目录</span></div>';document.getElementById('ft').textContent='';return}
el.innerHTML=T.map((t,i)=>'<div class="it'+(i===S?' on':'')+'" onclick="pick('+i+')"><span class="ic">fn</span>'+esc(t.name)+'</div>').join('');
document.getElementById('ft').textContent=T.length+' 个工具'}
function pick(i){
S=i;const t=T[i];
document.getElementById('emp').style.display='none';
document.getElementById('pnl').classList.add('on');
document.getElementById('tn').textContent=t.name;
document.getElementById('td').textContent=t.description||'暂无描述';
document.getElementById('ts').textContent=t.source;
document.getElementById('args').value='';
document.getElementById('res').classList.remove('on');
render();document.getElementById('args').focus()}
async function go(){
if(S<0)return;const t=T[S],btn=document.getElementById('rb');
btn.disabled=true;btn.innerHTML='<span class="sp"></span>执行中';
document.getElementById('res').classList.add('on');
document.getElementById('rs').innerHTML='<span class="sp"></span>';
document.getElementById('rt').textContent='';
document.getElementById('po').textContent='';
document.getElementById('pe').textContent='';
tab('stdout');
try{
const r=await(await fetch('/api/run',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({className:t.className,args:document.getElementById('args').value})})).json();
const ok=r.exitCode===0&&!r.stderr;
document.getElementById('rs').innerHTML=ok?'<span style="color:var(--ok)">✓ 成功</span>':'<span style="color:var(--er)">✕ 退出码 '+r.exitCode+'</span>';
document.getElementById('rt').textContent=r.ms+'ms';
document.getElementById('po').textContent=r.stdout||'(无输出)';
document.getElementById('pe').textContent=r.stderr||'(无错误输出)';
if(!ok&&r.stderr)tab('stderr')
}catch(e){document.getElementById('rs').innerHTML='<span style="color:var(--er)">✕ 错误</span>';document.getElementById('po').textContent=e.message}
btn.disabled=false;btn.innerHTML='▶ 运行'}
async function src(){if(S<0)return;try{const c=await(await fetch('/api/source?class='+encodeURIComponent(T[S].name))).text();document.getElementById('mt').textContent=T[S].source;document.getElementById('mc').textContent=c;document.getElementById('ov').classList.add('on')}catch(e){alert('获取失败')}}
function hsrc(){document.getElementById('ov').classList.remove('on')}
function tab(n){document.querySelectorAll('.tab').forEach(t=>t.classList.toggle('on',t.dataset.t===n));document.getElementById('po').style.display=n==='stdout'?'block':'none';document.getElementById('pe').style.display=n==='stderr'?'block':'none'}
function esc(s){const d=document.createElement('div');d.textContent=s;return d.innerHTML}
init();
</script>
</body>
</html>
""";
}
