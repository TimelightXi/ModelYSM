import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** 离线 dump .ysm：动画组/动画名/Molang 表达式/控制器/骨骼树 */
public class DumpYsm {
    public static void main(String[] args) throws Exception {
        byte[] ysm = Files.readAllBytes(Paths.get(args[0]));
        byte[] clear = YsmCrypt.decryptYsmFile(ysm);
        RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();
        System.out.println("format=" + raw.formatVersion + " name=" + raw.metadata.name);
        System.out.println("props: widthScale=" + raw.properties.widthScale + " heightScale=" + raw.properties.heightScale
                + " defaultTex=" + raw.properties.defaultTexture);
        System.out.println("extraAnimations=" + raw.properties.extraAnimations);

        System.out.println("\n== bones (" + raw.mainEntity.mainModel.bones.size() + ") ==");
        for (RawYsmModel.RawBone b : raw.mainEntity.mainModel.bones) {
            System.out.printf("  %-22s parent=%-20s cubes=%d pivot=%.1f,%.1f,%.1f rot=%.4f,%.4f,%.4f unk=%d,%d,%d,%d,%d%n",
                    b.name, b.parentName, b.cubes.size(), b.pivot[0], b.pivot[1], b.pivot[2],
                    b.rotation[0], b.rotation[1], b.rotation[2],
                    b.unkPad1, b.unkPad2, b.unkPad3, b.unkPad4, b.unkPad5);
        }

        System.out.println("\n== animation files ==");
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> e : raw.mainEntity.animationFiles.entrySet()) {
            System.out.println(" group=" + e.getKey() + " type=" + e.getValue().animType + " anims=" + e.getValue().animations.keySet());
        }

        System.out.println("\n== key animations detail (first 2 frames of each channel expr) ==");
        RawYsmModel.RawAnimationFile main = raw.mainEntity.animationFiles.get("main");
        if (main != null) {
            for (RawYsmModel.RawAnimation ra : main.animations.values()) {
                System.out.println(" anim '" + ra.name + "' len=" + ra.length + " loop=" + ra.loopMode);
                for (RawYsmModel.RawBoneAnimation ba : ra.boneAnimations) {
                    StringBuilder sb = new StringBuilder("   " + ba.boneName + ":");
                    sb.append(" rot").append(ba.rotation.size());
                    if (!ba.rotation.isEmpty()) sb.append("[" + expr(ba.rotation.get(0)) + "]");
                    sb.append(" pos").append(ba.position.size());
                    if (!ba.position.isEmpty()) sb.append("[" + expr(ba.position.get(0)) + "]");
                    sb.append(" scl").append(ba.scale.size());
                    if (!ba.scale.isEmpty()) sb.append("[" + expr(ba.scale.get(0)) + "]");
                    System.out.println(sb);
                }
            }
        }


        System.out.println("\n== function files ==");
        for (Map.Entry<String, RawYsmModel.RawDataFile> e : raw.functionFiles.entrySet()) {
            byte[] d = e.getValue().data;
            String s = d == null ? "(null)" : new String(d, java.nio.charset.StandardCharsets.UTF_8);
            System.out.println(" func '" + e.getKey() + "' len=" + (d==null?0:d.length) + ": " +
                s.substring(0, Math.min(s.length(), 1500)));
        }
        System.out.println("\n== sound files keys ==");
        System.out.println(raw.soundFiles.keySet());

        System.out.println("\n== RAW KEYFRAMES: idle AllBody/LeftBackHair1_1 ==");
        if (main != null) {
            RawYsmModel.RawAnimation idle = main.animations.get("walk");
            for (RawYsmModel.RawBoneAnimation ba : idle.boneAnimations) {
                if (!ba.boneName.equals("LeftBackHair1_1") && !ba.boneName.equals("LeftBackHair1_3")) continue;
                System.out.println(" BONE " + ba.boneName + " rot=" + ba.rotation.size() + " pos=" + ba.position.size());
                int n = 0;
                for (RawYsmModel.RawKeyframe kf : ba.rotation) {
                    System.out.println("   rot t=" + kf.timestamp + " interp=" + kf.interpolationMode
                        + " hasPre=" + kf.hasPreData
                        + " post=" + java.util.Arrays.toString(kf.postData)
                        + " pre=" + java.util.Arrays.toString(kf.preData));
                    if (++n >= 8) { System.out.println("   ..."); break; }
                }
                n = 0;
                for (RawYsmModel.RawKeyframe kf : ba.position) {
                    System.out.println("   pos t=" + kf.timestamp + " interp=" + kf.interpolationMode
                        + " post=" + java.util.Arrays.toString(kf.postData));
                    if (++n >= 8) { System.out.println("   ..."); break; }
                }
            }
        }
        System.out.println("\n== controllers ==");
        for (Map.Entry<String, RawYsmModel.RawAnimationController> e : raw.mainEntity.animationControllers.entrySet()) {
            RawYsmModel.RawAnimationController c = e.getValue();
            System.out.println(" ctrl '" + c.name + "' anim=" + c.animationName + " init=" + c.initialState);
            for (RawYsmModel.RawControllerState st : c.states) {
                System.out.println("   state '" + st.name + "' anims=" + st.animations + " trans=" + st.transitions);
            }
        }
    }

    static String expr(RawYsmModel.RawKeyframe kf) {
        StringBuilder sb = new StringBuilder();
        sb.append("t=").append(kf.timestamp);
        sb.append(" post=");
        for (Object o : kf.postData) {
            if (o instanceof Float f) sb.append(f).append(",");
            else if (o instanceof String s) sb.append("'").append(s.length() > 120 ? s.substring(0, 120) + "..." : s).append("',");
            else sb.append(o).append(",");
        }
        return sb.toString();
    }
}
