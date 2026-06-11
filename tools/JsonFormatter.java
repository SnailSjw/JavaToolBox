/**
 * JSON 格式化工具 — 将压缩的 JSON 字符串格式化为易读格式
 * 参数: 压缩的 JSON 字符串
 */
public class JsonFormatter {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("用法: JsonFormatter <json-string>");
            return;
        }
        String input = args[0];
        int indent = 0;
        boolean inQuote = false;
        for (char c : input.toCharArray()) {
            if (c == '"' && (indent == 0 || input.charAt(input.indexOf(c) - 1) != '\\'))
                inQuote = !inQuote;
            if (!inQuote) {
                if (c == '{' || c == '[') { System.out.println(c); indent++; printIndent(indent); }
                else if (c == '}' || c == ']') { System.out.println(); indent--; printIndent(indent); System.out.print(c); }
                else if (c == ',') { System.out.println(c); printIndent(indent); }
                else System.out.print(c);
            } else System.out.print(c);
        }
        System.out.println();
    }
    static void printIndent(int n) { for (int i = 0; i < n; i++) System.out.print("  "); }
}
