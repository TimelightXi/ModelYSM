import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 离线诊断：解析 .ysm 二进制，打印 properties.extra_animation_buttons（⚙ 配置表单）
 * 与 extra_animation（轮盘条目），验证模型文件是否携带官方配置数据。
 */
public class DumpButtons {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            System.out.println("=== " + path + " ===");
            byte[] clear = YsmCrypt.decryptYsmFile(Files.readAllBytes(Paths.get(path)));
            RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();
            System.out.println("format=" + raw.formatVersion);
            if (raw.properties == null) { System.out.println("properties=null"); continue; }
            System.out.println("extra_animation entries=" + raw.properties.extraAnimations.size());
            int shown = 0;
            for (var e : raw.properties.extraAnimations.entrySet()) {
                System.out.println("  " + e.getKey() + " -> " + e.getValue());
                if (++shown >= 12) { System.out.println("  ..."); break; }
            }
            System.out.println("extra_animation_buttons=" + raw.properties.extraAnimationButtons.size());
            // 语言表中 config_forms i18n key 数量（验证 title/description/labels 本地化是否命中）
            java.util.Map<String, Integer> langHits = new java.util.LinkedHashMap<>();
            for (var le : raw.languageFiles.entrySet()) {
                if (le.getValue() == null || le.getValue().data == null) continue;
                int n = 0;
                for (String k : le.getValue().data.keySet()) {
                    if (k.contains("extra_animation_buttons")) n++;
                }
                langHits.put(le.getKey(), n);
            }
            System.out.println("languageFiles extra_animation_buttons keys: " + langHits);
            for (RawYsmModel.ExtraAnimationButton b : raw.properties.extraAnimationButtons) {
                System.out.println("  button id=" + b.id + " name=" + b.name);
                for (RawYsmModel.ConfigForm f : b.forms) {
                    System.out.println("    form type=" + f.type + " title=" + f.title
                            + " value=" + f.defaultValue
                            + (f.step != 0 ? " step=" + f.step + " min=" + f.min + " max=" + f.max : ""));
                    for (var le : f.labels.entrySet()) {
                        System.out.println("      label '" + le.getKey() + "' -> " + le.getValue());
                    }
                }
            }
        }
    }
}
