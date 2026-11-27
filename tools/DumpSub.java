import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;
import java.nio.file.Files;
import java.nio.file.Paths;

public class DumpSub {
    public static void main(String[] args) throws Exception {
        for (String path : args) {
            byte[] clear = YsmCrypt.decryptYsmFile(Files.readAllBytes(Paths.get(path)));
            RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();
            System.out.println("=== " + path);
            System.out.println("vehicles=" + raw.vehicles.size() + " projectiles=" + raw.projectiles.size());
            for (var e : raw.vehicles.entrySet()) {
                RawYsmModel.RawSubEntity s = e.getValue();
                System.out.println("  vehicle id=" + e.getKey() + " match=" + java.util.Arrays.toString(s.matchIds)
                        + " bones=" + (s.model != null && s.model.bones != null ? s.model.bones.size() : 0)
                        + " animFiles=" + (s.animationFiles == null ? 0 : s.animationFiles.size()));
            }
            for (var e : raw.projectiles.entrySet()) {
                RawYsmModel.RawSubEntity s = e.getValue();
                System.out.println("  projectile id=" + e.getKey() + " match=" + java.util.Arrays.toString(s.matchIds)
                        + " bones=" + (s.model != null && s.model.bones != null ? s.model.bones.size() : 0)
                        + " animFiles=" + (s.animationFiles == null ? 0 : s.animationFiles.size()));
            }
        }
    }
}
