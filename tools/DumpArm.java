import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DumpArm {
    public static void main(String[] args) throws Exception {
        byte[] clear = YsmCrypt.decryptYsmFile(Files.readAllBytes(Paths.get(args[0])));
        RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();
        System.out.println("armModel=" + (raw.mainEntity.armModel != null
                ? raw.mainEntity.armModel.bones.size() + " bones" : "null"));
        System.out.println("animationFiles keys=" + raw.mainEntity.animationFiles.keySet());
        var af = raw.mainEntity.animationFiles.get("fp.arm");
        if (af != null) System.out.println("fp.arm anims=" + af.animations.keySet());
        var arm = raw.mainEntity.animationFiles.get("arm");
        if (arm != null) System.out.println("arm anims=" + arm.animations.keySet());
    }
}
