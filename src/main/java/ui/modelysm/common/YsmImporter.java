package ui.modelysm.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import ui.modelysm.ModelYSM;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * .ysm 模型自动导入：扫描 config/modelysm/models/ 下的 *.ysm 文件，
 * 调用 YSMParser（OpenYSM 项目，https://github.com/OpenYSM/YSMParser）解密为标准
 * Bedrock 工程目录（models/ + animations/ + textures/ + ysm.json），再由 ModelLibrary 加载。
 *
 * 关键约定：
 *   - 原 .ysm 文件【永远留在原地，绝不移动/删除/改名】——复制到临时目录解密，
 *     解密产物放到 models/&lt;ysm名&gt;/ 文件夹；删除 .ysm 前对应的解密文件夹依然可用。
 *   - YSMParser 是 C++ 程序，无法处理含非 ASCII 字符的 -i/-o 路径（报
 *     "No mapping for the Unicode character"），因此工作目录固定放在
 *     java.io.tmpdir（通常为 C:\Users\&lt;名&gt;\AppData\Local\Temp，纯 ASCII）。
 *     工作目录【内】的中文文件名没有问题。
 *   - 增量导入：models/&lt;ysm名&gt;/ 已存在且比 .ysm 新时跳过，改了 .ysm 会自动重新解密。
 *
 * 解密器定位（按优先级）：
 *   1. config/modelysm/ysmparser.txt 第一行写的 exe 绝对路径
 *   2. config/modelysm/tools/ 下的 exe（文件名含 "ysmparser" 优先）
 */
public final class YsmImporter {
    private static final Logger LOGGER = LogManager.getLogger(ModelYSM.MODID);
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    private YsmImporter() {}

    /** 后台线程导入（进服时调用，避免卡主线程） */
    public static void importAsync() {
        Thread t = new Thread(YsmImporter::importIfNeeded, "ModelYSM-YsmImport");
        t.setDaemon(true);
        t.start();
    }

    /** 同步导入（无 .ysm 时瞬间返回；GUI 打开前调用确保列表齐全） */
    public static synchronized void importIfNeeded() {
        if (!RUNNING.compareAndSet(false, true)) return;
        try {
            doImport();
        } catch (Exception e) {
            LOGGER.warn("[ModelYSM] ysm import failed: {}", e.toString());
        } finally {
            RUNNING.set(false);
        }
    }

    private static void doImport() {
        Path root = ModelFolderScanner.modelsRoot();
        if (!Files.isDirectory(root)) return;

        List<Path> ysms = new ArrayList<>();
        try (Stream<Path> s = Files.list(root)) {
            s.filter(p -> Files.isRegularFile(p)
                    && p.getFileName().toString().toLowerCase().endsWith(".ysm")).forEach(ysms::add);
        } catch (IOException e) {
            return;
        }
        if (ysms.isEmpty()) return;

        Path exe = findParser(root);
        if (exe == null) {
            LOGGER.warn("[ModelYSM] 发现 {} 个 .ysm 文件但未找到 YSMParser 解密器。"
                    + "请把 YSMParser.exe 放到 config/modelysm/tools/ 下（或在 config/modelysm/ysmparser.txt 里写 exe 路径）", ysms.size());
            return;
        }

        // 过滤掉已导入且未更新的文件
        List<Path> todo = new ArrayList<>();
        for (Path ysm : ysms) {
            String name = sanitize(baseName(ysm));
            Path target = root.resolve(name);
            if (Files.isDirectory(target) && !isNewer(ysm, target)) {
                continue; // 已导入，跳过
            }
            todo.add(ysm);
        }
        if (todo.isEmpty()) return;

        // 工作目录：必须在纯 ASCII 路径下（YSMParser 不支持非 ASCII 的 -i/-o 参数）
        Path work = Path.of(System.getProperty("java.io.tmpdir"), "modelysm_ysm_import");
        Path inDir = work.resolve("input");
        Path outDir = work.resolve("output");
        deleteQuietly(work); // 清理上次可能残留的内容
        try {
            Files.createDirectories(inDir);
            Files.createDirectories(outDir);
        } catch (IOException e) {
            LOGGER.warn("[ModelYSM] cannot create temp work dirs ({}): {}", work, e.getMessage());
            return;
        }

        // 复制（绝不移动原文件）到 ASCII 工作目录；同名冲突后到者跳过
        List<Path> copied = new ArrayList<>();
        for (Path ysm : todo) {
            try {
                Path target = inDir.resolve(ysm.getFileName().toString());
                if (!Files.exists(target)) {
                    Files.copy(ysm, target, StandardCopyOption.REPLACE_EXISTING);
                    copied.add(ysm);
                } else {
                    LOGGER.warn("[ModelYSM] skip duplicate ysm name: {}", ysm.getFileName());
                }
            } catch (IOException e) {
                LOGGER.warn("[ModelYSM] cannot copy {}: {}", ysm.getFileName(), e.getMessage());
            }
        }
        if (copied.isEmpty()) {
            deleteQuietly(work);
            return;
        }

        LOGGER.info("[ModelYSM] importing {} .ysm file(s) with {} (originals kept in place)",
                copied.size(), exe.getFileName());
        long start = System.currentTimeMillis();
        Process proc = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    exe.toAbsolutePath().toString(),
                    "-i", inDir.toAbsolutePath().toString(),
                    "-o", outDir.toAbsolutePath().toString(),
                    "-j", "4");
            // cwd 必须是 exe 所在目录——YSMParser 依赖同目录的 YSMParserJNI.dll
            pb.directory(exe.getParent().toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(work.resolve("import.log").toFile());
            proc = pb.start();
            if (!proc.waitFor(15, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                LOGGER.warn("[ModelYSM] YSMParser timed out after 15 min");
                saveLog(work, root);
                deleteQuietly(work);
                return;
            }
        } catch (Exception e) {
            LOGGER.warn("[ModelYSM] failed to run YSMParser: {}", e.toString());
            if (proc != null) proc.destroyForcibly();
            saveLog(work, root);
            deleteQuietly(work);
            return;
        }
        int exit = proc.exitValue();
        LOGGER.info("[ModelYSM] YSMParser exit={} in {}s", exit, (System.currentTimeMillis() - start) / 1000);

        // 输出目录 <outDir>/<名称>/ → models/<名称>/（覆盖旧版本）
        int done = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(outDir, Files::isDirectory)) {
            for (Path out : ds) {
                String name = sanitize(out.getFileName().toString());
                if (name.isBlank()) continue;
                Path target = root.resolve(name);
                try {
                    deleteQuietly(target); // 重新解密时覆盖旧文件夹
                    Files.move(out, target);
                    done++;
                    LOGGER.info("[ModelYSM] imported model '{}' -> {}/", name, target.getFileName());
                } catch (IOException e) {
                    LOGGER.warn("[ModelYSM] cannot install output {}: {}", name, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.warn("[ModelYSM] no output produced (exit={}): {}", exit, e.getMessage());
        }

        saveLog(work, root);
        deleteQuietly(work);
        LOGGER.info("[ModelYSM] ysm import done: {} installed, originals untouched", done);
    }

    /** .ysm 是否比已导入的模型文件夹新（需要重新解密） */
    private static boolean isNewer(Path ysm, Path targetDir) {
        try {
            Instant y = Files.getLastModifiedTime(ysm).toInstant();
            Instant t = Files.getLastModifiedTime(targetDir).toInstant();
            return y.isAfter(t);
        } catch (IOException e) {
            return true; // 无法判断时宁可重新导入
        }
    }

    /** 保留解析日志便于诊断（存到 config/modelysm/ysm_import_last.log） */
    private static void saveLog(Path work, Path root) {
        try {
            Path log = work.resolve("import.log");
            if (Files.isRegularFile(log)) {
                Files.copy(log, root.getParent().resolve("ysm_import_last.log"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ignored) {}
    }

    /** 定位解密器 exe */
    private static Path findParser(Path modelsRoot) {
        Path cfg = modelsRoot.getParent(); // config/modelysm
        // 1. ysmparser.txt 指定路径
        Path txt = cfg.resolve("ysmparser.txt");
        if (Files.isRegularFile(txt)) {
            try {
                String line = Files.readString(txt, StandardCharsets.UTF_8).trim();
                if (!line.isEmpty()) {
                    Path p = Path.of(line.strip());
                    if (Files.isRegularFile(p)) return p;
                    LOGGER.warn("[ModelYSM] ysmparser.txt path not found: {}", line);
                }
            } catch (IOException ignored) {}
        }
        // 2. tools/ 下的 exe
        Path tools = cfg.resolve("tools");
        if (Files.isDirectory(tools)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(tools, "*.exe")) {
                Path fallback = null;
                for (Path p : ds) {
                    String n = p.getFileName().toString().toLowerCase().replaceAll("[^a-z0-9]", "");
                    if (n.contains("ysmparser")) return p;
                    fallback = p; // tools 下唯一 exe 且不匹配名时也可尝试
                }
                if (fallback != null) return fallback;
            } catch (IOException ignored) {}
        }
        return null;
    }

    /** 清理文件名中的非法字符 */
    private static String sanitize(String name) {
        String s = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        while (s.endsWith(".") || s.endsWith(" ")) s = s.substring(0, s.length() - 1);
        return s;
    }

    private static String baseName(Path p) {
        String n = p.getFileName().toString();
        int i = n.lastIndexOf('.');
        return i > 0 ? n.substring(0, i) : n;
    }

    /** 递归删除目录（仅用于临时工作目录和旧解密产物） */
    private static void deleteQuietly(Path dir) {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
