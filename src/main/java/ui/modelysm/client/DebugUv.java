package ui.modelysm.client;

/**
 * 渲染约定配置（原来带 F7/F8/F9 实机切换键，2026-11-25 已按实机比对结果定死并移除按键）。
 * 定案组合：旋转档 A（-x,-y,+z / Z→Y→X） + UV 岛整数吸附 + 底面正向 V。
 */
public final class DebugUv {
    private DebugUv() {}

    /**
     * 旋转约定档位（0..3）。存储的 restRotRad/rotAnglesRad 是按 (-x,-y,+z) 生成的：
     *  0 = (-x,-y,+z)  Z→Y→X   （定案，A）
     *  1 = (+x,-y,-z)  Z→Y→X
     *  2 = (-x,-y,+z)  X→Y→Z
     *  3 = (+x,-y,-z)  X→Y→Z
     */
    public static int rotMode = 0;
    /** 0 = 盒式 UV 岛按分数尺寸；1 = 岛边界吸附到整数像素（默认） */
    public static int uvMode = 1;
    /** 0 = 底面 V 反向（vanilla Polygon 语义）；1 = 正向（默认） */
    public static int downMode = 1;

    /**
     * A/B 试验开关：只把 cube（方块）自身的 Z 旋转取反，骨头静止/动画旋转不受影响。
     * true  = 新口径（2026-11-25 试验：修“背包左上角多出黑色材质”）
     * false = 旧口径（回滚用）
     */
    public static boolean flipCubeZ = true;

    /** 是否把 X/Z 旋转取反（档 1、3） */
    public static boolean flipXZ() { return (rotMode & 1) != 0; }
    /** 是否用 X→Y→Z 顺序（档 2、3） */
    public static boolean orderXYZ() { return (rotMode & 2) != 0; }

    /** X 轴角：档 1/3 取反 */
    public static float ax(float stored) { return flipXZ() ? -stored : stored; }
    /** Y 轴角：两种约定都取反，存储值已是 -y，直接用 */
    public static float ay(float stored) { return stored; }
    /** Z 轴角：档 1/3 取反 */
    public static float az(float stored) { return flipXZ() ? -stored : stored; }

    public static String rotName() {
        return switch (rotMode) {
            case 0 -> "A  (-x,-y,+z)  Z→Y→X";
            case 1 -> "B  (+x,-y,-z)  Z→Y→X";
            case 2 -> "C  (-x,-y,+z)  X→Y→Z";
            default -> "D  (+x,-y,-z)  X→Y→Z";
        };
    }

    public static String uvName() { return uvMode == 0 ? "分数尺寸（当前）" : "整数吸附"; }

    public static String downName() { return downMode == 0 ? "反向 V（vanilla）" : "正向 V"; }
}
