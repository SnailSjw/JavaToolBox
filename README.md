# JavaToolBox

一个轻量级的 Java 工具箱平台，通过内置 HTTP 服务器和 Web 界面，将 `tools/` 目录下的 Java 工具类自动暴露为可交互的在线工具。

---

## 主要功能

- **自动扫描与注册** — 自动发现 `tools/` 目录下的 `public static` 方法，无需额外配置
- **Javadoc 解析** — 自动提取方法的文档注释，生成参数说明、返回值说明和异常说明
- **Web 交互界面** — 内置现代化的深色主题前端，支持搜索、参数填写、一键运行和查看源码
- **实时热重载** — 监听 `tools/` 目录的文件变更，保存后自动重新编译并刷新方法列表
- **REST API** — 提供 `/api/methods`、`/api/run`、`/api/refresh`、`/api/source` 等接口
- **多参数类型支持** — 自动转换 `String`、`int`、`long`、`double`、`boolean` 及 `String[]` 等参数类型
- **依赖管理** — 自动加载 `tools/lib/` 目录下的 `.jar` 包作为类路径依赖

---

## 项目结构

```
JavaToolBox/
├── JavaToolBox.java      # 主程序：HTTP 服务器、编译器、方法扫描器
├── run.bat               # Windows 一键启动脚本
├── run.sh                # macOS / Linux 一键启动脚本
├── README.md             # 本文件
├── tools/                # 工具类目录（放置你的 .java 工具类）
│   ├── lib/              # 第三方依赖 jar 包目录
│   ├── HashTool.java     # 哈希计算工具（MD5 / SHA-1 / SHA-256）
│   ├── JsonFormatter.java# JSON 格式化工具
│   └── Svg2Json.java     # SVG 与 JSON 互转工具
└── web/
    └── index.html        # Web 交互界面
```

---

## 环境要求

- **JDK 8 或更高版本**（必须包含 `javac`，用于运行时动态编译工具类）
- 操作系统：Windows、Linux、macOS 均可

---

## 快速开始

### Windows

双击运行 `run.bat`，脚本会自动检查 JDK、编译主程序并启动服务：

```batch
run.bat
```

### macOS / Linux

赋予执行权限后一键启动：

```bash
chmod +x run.sh
./run.sh
```

> **后台运行提示**：使用 `nohup ./run.sh &` 可在后台持续运行，关闭终端也不影响服务。

### 命令行手动启动

如需自定义端口或工具目录，可手动编译启动：

```bash
# 1. 编译主程序
javac -encoding UTF-8 -d tools/.build JavaToolBox.java

# 2. 启动服务（默认端口 8080）
java -Dfile.encoding=UTF-8 -cp tools/.build JavaToolBox ./tools 8080
```

启动成功后，打开浏览器访问：

```
http://localhost:8080
```

---

## 如何使用

### 1. 使用现有工具

在左侧列表中选择工具方法 → 填写参数 → 点击 **Run**（或按 `Ctrl + Enter`）→ 在右侧面板查看运行结果。

### 2. 添加自定义工具

只需在 `tools/` 目录下新建 `.java` 文件，编写 `public static` 方法即可：

```java
/**
 * 我的自定义工具
 */
public class MyTool {
    /**
     * 计算两数之和
     * @param a 第一个数
     * @param b 第二个数
     * @return 两数相加的结果
     */
    public static int add(int a, int b) {
        return a + b;
    }
}
```

保存文件后，JavaToolBox 会自动检测到变更、重新编译并将新方法注册到 Web 界面中。

### 3. 添加第三方依赖

将 `.jar` 文件放入 `tools/lib/` 目录，重启或触发刷新后即可在工具类中使用。

---

## 内置工具一览

| 工具类 | 方法 | 说明 |
|--------|------|------|
| `HashTool` | `main(String[] args)` | 计算字符串的 MD5 / SHA-1 / SHA-256 哈希值 |
| `JsonFormatter` | `main(String[] args)` | 将压缩的 JSON 字符串格式化为易读格式 |
| `Svg2Json` | `readSvgFile(...)` | 读取 SVG 文件并转为固定格式的 JSON |
| `Svg2Json` | `writeSvgToJson(...)` | 将 SVG 内容写入已有 JSON 的 `svgcontent` 字段 |
| `Svg2Json` | `extractSvgFromJson(...)` | 从 JSON 中提取 `svgcontent` 生成 SVG 文件 |

---

## API 接口

| 接口 | 方法 | 说明 |
|------|------|------|
| `/` | GET | 返回 Web 交互界面 |
| `/api/methods` | GET | 获取所有已注册的工具方法列表（JSON） |
| `/api/run` | POST | 执行指定方法，请求体：`{className, methodName, args: [...]}` |
| `/api/refresh` | GET | 手动触发重新扫描和编译 |
| `/api/source?class=类名` | GET | 获取指定类的源码 |

---

## 开源协议

本项目采用 **MIT License** 开源协议。

```
MIT License

Copyright (c) 2026 JavaToolBox Authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

> 欢迎 Star 和 Fork，也欢迎通过提交 PR 贡献更多实用工具！
