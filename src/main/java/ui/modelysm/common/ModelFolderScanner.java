package ui.modelysm.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ui.modelysm.ModelYSM;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * 模型文件夹扫描：config/modelysm/models/<模型id>/ 下
 *   main.json（几何） + main.animation.json（动画） + *.png（贴图）
 * 任一子文件夹即视为一个模型。
 */
public final class ModelFolderScanner {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);

    private ModelFolderScanner() {}

    public static Path modelsRoot() {
        return net.minecraftforge.fml.loading.FMLPaths.CONFIGDIR.get().resolve("modelysm").resolve("models");
    }

    /** 列出所有模型 id：子文件夹（跳过 ysm_* 工作目录）+ 根目录下的 *.ysm 文件（直读，id=去扩展名）；同名去重 */
    public static List<String> listModels() {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        Path root = modelsRoot();
        if (!Files.isDirectory(root)) return new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.forEach(p -> {
                String n = p.getFileName().toString();
                if (Files.isDirectory(p)) {
                    if (!n.startsWith("ysm_")) set.add(n);
                } else if (n.toLowerCase().endsWith(".ysm")) {
                    set.add(n.substring(0, n.length() - 4));
                }
            });
        } catch (IOException e) {
            LOGGER.warn("[ModelYSM] failed to scan models dir: {}", e.getMessage());
        }
        List<String> res = new ArrayList<>(set);
        res.sort(String::compareTo);
        return res;
    }

    public static Path modelDir(String id) {
        return modelsRoot().resolve(id);
    }
}
