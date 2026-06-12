# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

```bash
# Windows — one-click start (compiles + runs)
run.bat

# Linux/macOS — one-click start (compiles + runs)
chmod +x run.sh && ./run.sh

# Manual compile (for development)
javac -encoding UTF-8 -d tools/.build JavaToolBox.java

# Manual run (custom port)
java -Dfile.encoding=UTF-8 -cp tools/.build JavaToolBox ./tools 8080

# Run with background persistence (Linux/macOS)
nohup ./run.sh &
```

There are no test suites, linting, or build tools — this is a single-file JDK-only project with no external dependencies at the platform level.

## Architecture

**JavaToolBox.java** is the entire backend: HTTP server (via `com.sun.net.httpserver`), runtime Java compiler (via `javax.tools.JavaCompiler`), method scanner, Javadoc parser, file watcher, and out-of-process method executor — all in ~460 lines.

### Request flow

1. On boot, `main()` compiles itself into `tools/.build/`, then calls `rebuild()`.
2. `rebuild()` → `scan()` finds all `.java` files under `tools/` (excluding `.build/`), `compile()` javacs them with classpath including `tools/lib/*.jar` and `tools/.build/`, then `scanMethods()` uses regex to discover all `public static` methods and their Javadoc.
3. `boot()` writes a generated `MethodRunner.java` to `tools/.build/` — this is a standalone Java class that loads a tool class by name and calls the target method via reflection.
4. HTTP endpoints: `GET /` serves `web/index.html`; `GET /api/methods` returns all discovered methods as JSON; `POST /api/run` executes a method; `GET /api/refresh` triggers rebuild; `GET /api/source?class=…` returns source code.
5. Method execution: `execute()` spawns a **new Java process** running `MethodRunner` with the tool classpath — this isolates tool execution from the main server. Timeout is 30 seconds.
6. A `WatchService` on `tools/` detects `.java` file changes and auto-rebuilds after a 600ms debounce.

### Key design decisions

- **No package** — `JavaToolBox.java`, tool classes, and the generated `MethodRunner` all live in the default package. The classpath includes both `tools/.build/` and `tools/lib/*.jar`.
- **Javadoc parsing is custom regex** — `ParsedJavadoc.parse()` handles `@param`, `@return`/`@returns`, `@throws`/`@exception`, and strips `{@code}`/`{@link}` inline tags. It does NOT use any doclet or AST API.
- **Method discovery is regex-based** — pattern `public static <ret> <name>(<params>)` without any AST parsing. Generic types in parameters are handled by `splitParams()` which respects angle-bracket nesting.
- **Parameter type conversion** happens inside `MethodRunner.java`: `String`, `int`, `long`, `double`, `float`, `boolean`, `byte`, `short`, `char`, and `String[]` (varargs). Unknown types are passed as strings.
- **Hot-reload is compile-then-replace** — `rebuild()` clears the method list and re-scans entirely on each change; there's no incremental update.

### Directory layout

```
JavaToolBox.java          # Entire backend: server, compiler, scanner, executor
web/index.html            # Single-page frontend (dark theme, vanilla JS, no framework)
tools/                    # User tool classes (scanned automatically)
  .build/                 # Compiled .class files + generated MethodRunner.java
  lib/                    # Third-party .jar dependencies (e.g., fastjson-1.2.72.jar)
  HashTool.java           # Example: MD5/SHA hashing
  JsonFormatter.java      # Example: JSON pretty-printer
  Svg2Json.java           # Example: SVG ↔ JSON conversion
run.bat / run.sh          # Launch scripts
```

### Adding a new built-in tool

Create a `.java` file in `tools/` with a `public class` containing `public static` methods. Add Javadoc (class-level and method-level with `@param`, `@return`, `@throws`) and it will appear in the web UI automatically after save.

### Encoding

Everything uses UTF-8: source files, compilation (`-encoding UTF-8`), runtime (`-Dfile.encoding=UTF-8`), and HTTP responses. The Windows batch script sets `chcp 65001` for console output.
