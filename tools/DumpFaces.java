import ui.modelysm.client.ysm.RawYsmModel;
import ui.modelysm.client.ysm.YSMBinaryDeserializer;
import ui.modelysm.client.ysm.crypto.YsmCrypt;

import java.nio.file.Files;
import java.nio.file.Paths;

/** 离线诊断：dump 指定骨骼的面片顶点原始值，核对坐标空间 */
public class DumpFaces {
    public static void main(String[] args) throws Exception {
        byte[] clear = YsmCrypt.decryptYsmFile(Files.readAllBytes(Paths.get(args[0])));
        RawYsmModel raw = new YSMBinaryDeserializer(clear).deserialize();
        String want = args.length > 1 ? args[1] : "RightArm";
        for (RawYsmModel.RawBone b : raw.mainEntity.mainModel.bones) {
            if (!b.name.equalsIgnoreCase(want)) continue;
            System.out.println("bone=" + b.name + " pivot=" + b.pivot[0] + "," + b.pivot[1] + "," + b.pivot[2]);
            for (RawYsmModel.RawCube c : b.cubes) {
                for (RawYsmModel.RawFace f : c.faces) {
                    System.out.print("  face n=" + f.normal[0] + "," + f.normal[1] + "," + f.normal[2] + " pos:");
                    for (float[] p : f.positions) System.out.printf(" [%.4f,%.4f,%.4f]", p[0], p[1], p[2]);
                    System.out.println();
                }
            }
        }
    }
}
