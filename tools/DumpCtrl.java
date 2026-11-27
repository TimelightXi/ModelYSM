import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;
import java.nio.file.Files;
import java.nio.file.Paths;

/** 离线诊断：打印 .ysm 内 animation_controllers（状态机/条件动画数据） */
public class DumpCtrl {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            System.out.println("=== " + path + " ===");
            byte[] clear = YsmCrypt.decryptYsmFile(Files.readAllBytes(Paths.get(path)));
            RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();
            System.out.println("controllers=" + raw.mainEntity.animationControllers.size());
            for (var e : raw.mainEntity.animationControllers.entrySet()) {
                RawYsmModel.RawAnimationController c = e.getValue();
                System.out.println("CTRL: " + e.getKey() + " / name=" + c.name + " initial=" + c.initialState);
                for (RawYsmModel.RawControllerState s : c.states) {
                    System.out.println("  state=" + s.name + " anims=" + s.animations.keySet()
                            + " trans=" + s.transitions.size());
                    int i = 0;
                    for (var t : s.transitions.entrySet()) {
                        if (i++ < 3) System.out.println("    -> " + t.getKey() + " : " + t.getValue());
                    }
                }
            }
        }
    }
}
