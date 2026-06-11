import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.UUID;

public class Svg2Json {
    public static void main(String[] args) {

        // readSvgFile("docs/temp/网络拓扑图.svg","docs/temp/网络拓扑图.json");

        // writeSvgToJson("docs/123/网络拓扑图.svg", "docs/123/网络拓扑图.json");



        extractSvgFromJson("docs/json2svg/20260611实验室电力系统图.json","docs/json2svg/20260611实验室电力系统图.svg");
    }

    /**
     * 读取指定路径下的svg文件，并将该文件转为固定的json格式，输出到与SVG同目录的同名.json文件
     *
     * @param filePath svg文件路径
     */
    public static void readSvgFile(String filePath) {
        readSvgFile(filePath, null);
    }

    /**
     * 读取指定路径下的svg文件，并将该文件转为固定的json格式
     * 其中json文件其他值不变，只有svgcontent的值需要替换为svg文件的内容，注意svg文件的换行符需要替换为\n，双引号要替换为\"
     *
     * @param filePath   svg文件路径
     * @param outputPath 输出路径：为null或空时输出到SVG同目录同名.json；
     *                   如果以.json结尾则直接作为目标文件路径；
     *                   否则作为目录，拼接SVG文件名+.json
     */
    public static void readSvgFile(String filePath, String outputPath) {
        try {
            // 1. 读取SVG文件内容
            String svgContent = new String(Files.readAllBytes(Paths.get(filePath)), StandardCharsets.UTF_8);

            // 2. 解析SVG文件，提取元素信息
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new File(filePath));
            Element svgRoot = doc.getDocumentElement();

            // 3. 构建固定格式的JSON
            JSONObject json = new JSONObject();
            json.put("id", "v_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

            // 从文件名提取name
            String fileName = new File(filePath).getName();
            json.put("name", fileName.contains(".") ? fileName.substring(0, fileName.lastIndexOf(".")) : fileName);

            // 4. 构建profile（从SVG根元素提取宽高）
            JSONObject profile = new JSONObject();
            profile.put("width", parseSvgDimension(svgRoot.getAttribute("width"), 1600));
            profile.put("height", parseSvgDimension(svgRoot.getAttribute("height"), 900));
            profile.put("bkcolor", "#0e1117ff");
            profile.put("margin", 10);
            json.put("profile", profile);

            // 5. 解析SVG中带有stroke属性的rect元素，构建items
            JSONObject items = new JSONObject();
            NodeList rectList = doc.getElementsByTagName("rect");
            int shapeIndex = 1;
            for (int i = 0; i < rectList.getLength(); i++) {
                Element rect = (Element) rectList.item(i);
                String id = rect.getAttribute("id");
                String stroke = rect.getAttribute("stroke");
                // 只提取有id且以svg_开头、有stroke属性的大矩形（主要区域）
                if (!id.isEmpty() && id.startsWith("svg_") && !stroke.isEmpty()) {
                    // 判断是否为大区域矩形（宽高较大）
                    int w = parseSvgDimension(rect.getAttribute("width"), 0);
                    int h = parseSvgDimension(rect.getAttribute("height"), 0);
                    if (w >= 300 && h >= 200) {
                        JSONObject item = new JSONObject();
                        item.put("id", id);
                        item.put("type", "svg-ext-shapes-text");
                        item.put("name", "shape_" + shapeIndex++);

                        JSONObject property = new JSONObject();
                        JSONArray events = new JSONArray();
                        JSONObject event = new JSONObject();
                        event.put("actoptions", new JSONObject());
                        event.put("type", "click");
                        event.put("action", "onpage");
                        event.put("actparam", "v_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                        events.add(event);
                        property.put("events", events);
                        property.put("actions", new JSONArray());

                        JSONArray ranges = new JSONArray();
                        JSONObject range = new JSONObject();
                        range.put("type", 2);
                        range.put("min", 20);
                        range.put("max", 80);
                        range.put("color", "");
                        range.put("stroke", "");
                        ranges.add(range);
                        property.put("ranges", ranges);

                        item.put("property", property);
                        item.put("label", "Shapes");
                        items.put(id, item);
                    }
                }
            }
            json.put("items", items);

            // 6. variables固定为空对象
            json.put("variables", new JSONObject());

            // 7. 设置svgcontent（fastjson序列化时自动处理换行符→\n，双引号→\"）
            json.put("svgcontent", svgContent);

            // 8. 固定type和project
            json.put("type", "svg");
            json.put("project", "p_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));

            // 9. 确定输出路径
            if (outputPath == null || outputPath.trim().isEmpty()) {
                // 默认：与SVG同目录，扩展名改为.json
                outputPath = filePath.replaceAll("\\.svg$", ".json");
                if (outputPath.equals(filePath)) {
                    outputPath = filePath + ".json";
                }
            } else if (!outputPath.toLowerCase().endsWith(".json")) {
                // 指定了目录，拼接SVG文件名
                File svgFile = new File(filePath);
                String svgName = svgFile.getName();
                String jsonName = svgName.contains(".") ? svgName.substring(0, svgName.lastIndexOf(".")) + ".json" : svgName + ".json";
                outputPath = outputPath.replaceAll("[\\\\/]+$", "") + File.separator + jsonName;
            }
            // 如果outputPath以.json结尾，则直接使用该路径

            // 确保父目录存在
            File parentDir = new File(outputPath).getParentFile();
            if (parentDir != null && !parentDir.exists()) {
                parentDir.mkdirs();
            }

            String jsonStr = json.toJSONString();
            Files.write(Paths.get(outputPath), jsonStr.getBytes(StandardCharsets.UTF_8));

            System.out.println("SVG转JSON完成，输出文件：" + outputPath);
        } catch (Exception e) {
            System.err.println("SVG转JSON失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 读取指定路径的svg文件和json文件，将svg内容写入到json的svgcontent字段中
     * json文件其他内容保持不变
     *
     * @param svgFilePath svg文件路径
     * @param jsonFilePath json文件路径
     */
    public static void writeSvgToJson(String svgFilePath, String jsonFilePath) {
        try {
            // 1. 读取SVG文件内容
            String svgContent = new String(Files.readAllBytes(Paths.get(svgFilePath)), StandardCharsets.UTF_8);

            // 2. 读取JSON文件内容
            String jsonContent = new String(Files.readAllBytes(Paths.get(jsonFilePath)), StandardCharsets.UTF_8);
            JSONObject json = JSONObject.parseObject(jsonContent);

            // 3. 替换svgcontent字段（fastjson序列化时自动处理换行符→\n，双引号→\"）
            json.put("svgcontent", svgContent);

            // 4. 写回JSON文件
            String jsonStr = json.toJSONString();
            Files.write(Paths.get(jsonFilePath), jsonStr.getBytes(StandardCharsets.UTF_8));

            System.out.println("SVG写入JSON完成，目标文件：" + jsonFilePath);
        } catch (Exception e) {
            System.err.println("SVG写入JSON失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 从JSON文件中提取svgcontent并生成SVG文件，输出到与JSON同目录的同名.svg文件
     *
     * @param jsonFilePath json文件路径
     */
    public static void extractSvgFromJson(String jsonFilePath) {
        String outputPath = jsonFilePath.replaceAll("\\.json$", ".svg");
        if (outputPath.equals(jsonFilePath)) {
            outputPath = jsonFilePath + ".svg";
        }
        extractSvgFromJson(jsonFilePath, outputPath);
    }

    /**
     * 从JSON文件中提取svgcontent并生成指定路径的SVG文件
     *
     * @param jsonFilePath json文件路径
     * @param svgFilePath  生成的svg文件路径
     */
    public static void extractSvgFromJson(String jsonFilePath, String svgFilePath) {
        try {
            // 1. 读取JSON文件内容
            String jsonContent = new String(Files.readAllBytes(Paths.get(jsonFilePath)), StandardCharsets.UTF_8);
            JSONObject json = JSONObject.parseObject(jsonContent);

            // 2. 获取svgcontent字段（fastjson自动将转义序列还原为原始字符）
            String svgContent = json.getString("svgcontent");
            if (svgContent == null || svgContent.isEmpty()) {
                System.err.println("JSON中未找到svgcontent字段或内容为空");
                return;
            }

            // 3. 写入SVG文件
            Files.write(Paths.get(svgFilePath), svgContent.getBytes(StandardCharsets.UTF_8));

            System.out.println("SVG提取完成，输出文件：" + svgFilePath);
        } catch (Exception e) {
            System.err.println("SVG提取失败：" + e.getMessage());
            e.printStackTrace();
        }
    }

    /**
     * 解析SVG的宽高属性值，支持纯数字和带单位（如"1600px"）的格式
     *
     * @param attrVal    SVG属性值
     * @param defaultVal 解析失败时的默认值
     * @return 解析后的整数值
     */
    private static int parseSvgDimension(String attrVal, int defaultVal) {
        if (attrVal == null || attrVal.isEmpty()) {
            return defaultVal;
        }
        try {
            String numStr = attrVal.replaceAll("[^0-9.]", "");
            if (numStr.isEmpty()) {
                return defaultVal;
            }
            return (int) Double.parseDouble(numStr);
        } catch (NumberFormatException e) {
            return defaultVal;
        }
    }
}
