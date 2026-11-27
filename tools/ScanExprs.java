import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** 全帧扫描：收集所有动画所有关键帧的 molang 表达式，按变量/函数归类 */
public class ScanExprs {
    static Map<String, Integer> varCount = new TreeMap<>();
    static Map<String, Set<String>> varAnims = new TreeMap<>();

    static void feed(String anim, String expr) {
        if (expr == null || expr.isEmpty()) return;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(v|q|query|ysm|ctrl|math)\\.[A-Za-z0-9_.]+").matcher(expr);
        while (m.find()) {
            String key = m.group();
            varCount.merge(key, 1, Integer::sum);
            varAnims.computeIfAbsent(key, k -> new TreeSet<>()).add(anim);
        }
    }

    static void walkKeyframes(String anim, List<RawYsmModel.RawKeyframe> frames) {
        for (RawYsmModel.RawKeyframe kf : frames) {
            for (int i = 0; i < 3; i++) {
                feed(anim, str(kf.postData[i]));
                feed(anim, str(kf.preData[i]));
            }
        }
    }

    static String str(Object o) {
        return o == null ? "" : o.toString();
    }

    public static void main(String[] args) throws Exception {
        byte[] ysm = Files.readAllBytes(Paths.get(args[0]));
        byte[] clear = YsmCrypt.decryptYsmFile(ysm);
        RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> e : raw.mainEntity.animationFiles.entrySet()) {
            for (RawYsmModel.RawAnimation ra : e.getValue().animations.values()) {
                for (RawYsmModel.RawBoneAnimation ba : ra.boneAnimations) {
                    walkKeyframes(ra.name, ba.rotation);
                    walkKeyframes(ra.name, ba.position);
                    walkKeyframes(ra.name, ba.scale);
                }
            }
        }
        System.out.println("== 表达式符号统计（出现次数 <- 出现的动画）==");
        for (Map.Entry<String, Integer> e : varCount.entrySet()) {
            Set<String> anims = varAnims.get(e.getKey());
            String show = anims.size() <= 8 ? anims.toString() : anims.size() + "个动画:" + new ArrayList<>(anims).subList(0, 8);
            System.out.println("  " + e.getKey() + " x" + e.getValue() + "  <- " + show);
        }
    }
}
