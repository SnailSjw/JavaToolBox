import java.security.MessageDigest;

/**
 * 哈希计算工具 — 支持 MD5 / SHA-1 / SHA-256
 * 参数: arg1=算法名  arg2=待计算的字符串
 */
public class HashTool {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("用法: HashTool <MD5|SHA-1|SHA-256> <string>");
            return;
        }
        MessageDigest md = MessageDigest.getInstance(args[0]);
        byte[] digest = md.digest(args[1].getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : digest) sb.append(String.format("%02x", b & 0xff));
        System.out.println(args[0] + " = " + sb);
    }
}
