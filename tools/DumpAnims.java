import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

/** 打印指定动画的全部骨骼/全部关键帧（含 pre/post 表达式） */
public class DumpAnims {
    static String str(Object o) {
        if (o == null) return "0";
        if (o instanceof String s) return "'" + s + "'";
        return o.toString();
    }

    static void dumpChannel(String anim, String bone, String ch, List<RawYsmModel.RawKeyframe> frames) {
        if (frames.isEmpty()) return;
        StringBuilder sb = new StringBuilder("   " + bone + " " + ch + ":");
        for (RawYsmModel.RawKeyframe kf : frames) {
            sb.append(" [t=").append(kf.timestamp);
            if (kf.hasPreData) sb.append(" pre=").append(str(kf.preData[0])).append(",").append(str(kf.preData[1])).append(",").append(str(kf.preData[2]));
            sb.append(" post=").append(str(kf.postData[0])).append(",").append(str(kf.postData[1])).append(",").append(str(kf.postData[2])).append("]");
        }
        System.out.println(sb);
    }

    public static void main(String[] args) throws Exception {
        byte[] ysm = Files.readAllBytes(Paths.get(args[0]));
        byte[] clear = YsmCrypt.decryptYsmFile(ysm);
        RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();
        Set<String> wanted = new HashSet<>(Arrays.asList(args).subList(1, args.length));
        for (Map.Entry<String, RawYsmModel.RawAnimationFile> e : raw.mainEntity.animationFiles.entrySet()) {
            for (RawYsmModel.RawAnimation ra : e.getValue().animations.values()) {
                if (!wanted.isEmpty() && !wanted.contains(ra.name)) continue;
                System.out.println("anim '" + ra.name + "' len=" + ra.length + " loop=" + ra.loopMode
                        + " blend=" + ra.blendWeight + " unk=" + ra.unkInt1 + "," + ra.unkInt2 + "," + ra.unkInt4);
                for (RawYsmModel.RawBoneAnimation ba : ra.boneAnimations) {
                    dumpChannel(ra.name, ba.boneName, "rot", ba.rotation);
                    dumpChannel(ra.name, ba.boneName, "pos", ba.position);
                    dumpChannel(ra.name, ba.boneName, "scl", ba.scale);
                }
            }
        }
        // 控制器全量
        System.out.println("\n== controllers ==");
        for (RawYsmModel.RawAnimationController c : raw.mainEntity.animationControllers.values()) {
            System.out.println(" controller '" + c.name + "' initial=" + c.initialState);
            for (RawYsmModel.RawControllerState st : c.states) {
                System.out.println("  state '" + st.name + "' anims=" + st.animations);
                System.out.println("    trans=" + st.transitions);
            }
        }
    }
}
