package ui.modelysm.client.ysm.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.io.IOException;

/**
 * YSM zstd 魔改洗白（从 OpenYSM rip.ysm.algorithms.YsmZstd 移植，去掉 native 分支）。
 * YSM 导出的 zstd 流被魔改：块类型置换、cSize XOR 0xD4E9、帧头带校验位。
 * wash() 把它还原为标准 zstd 流，再用纯 Java zstd（zstd 包，aircompressor fork）解压。
 */
public final class YsmZstd {
    private YsmZstd() {}

    public static byte[] decompress(byte[] rawData) throws IOException {
        byte[] data = wash(rawData);
        return ui.modelysm.client.ysm.zstd.ZstdUtil.decompress(data);
    }

    private static byte[] wash(byte[] data) {
        if (data == null || data.length < 5) {
            throw new IllegalArgumentException("Invalid data length");
        }
        ByteBuffer buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        int magic = buffer.getInt(0);
        if (magic != 0xFD2FB528) {
            throw new IllegalArgumentException("Not a standard ZSTD Magic Number. May be skippable frame or unknown.");
        }
        byte fhd = data[4];
        data[4] = (byte) (fhd & 0xFB); // 清除 checksum 标志位（绕过 XXHash 魔改）
        int frameHeaderSize = calculateFrameHeaderSize(fhd);
        int offset = 4 + frameHeaderSize;
        while (offset + 3 <= data.length) {
            int b0 = data[offset] & 0xFF;
            int b1 = data[offset + 1] & 0xFF;
            int b2 = data[offset + 2] & 0xFF;
            int lastBlock = (b0 >> 7) & 1;
            int blockTypeYSM = (b0 >> 5) & 3;
            int rawSize = ((b0 & 0x1F) << 16) | b1 | (b2 << 8);
            int cSize = rawSize ^ 0xD4E9;
            int blockTypeStd = switch (blockTypeYSM) {
                case 0 -> 2;
                case 1 -> 1;
                case 2 -> 3;
                case 3 -> 0;
                default -> throw new IllegalStateException("Unknown block type");
            };
            int stdHeader = lastBlock | (blockTypeStd << 1) | (cSize << 3);
            data[offset] = (byte) (stdHeader & 0xFF);
            data[offset + 1] = (byte) ((stdHeader >> 8) & 0xFF);
            data[offset + 2] = (byte) ((stdHeader >> 16) & 0xFF);
            int blockDataSize = (blockTypeStd == 1) ? 1 : cSize;
            offset += 3 + blockDataSize;
            if (lastBlock == 1) break;
        }
        return data;
    }

    private static int calculateFrameHeaderSize(byte fhd) {
        int size = 1;
        boolean singleSegment = ((fhd >> 5) & 1) == 1;
        int dictIdBits = fhd & 3;
        int dictIdSize = dictIdBits == 1 ? 1 : dictIdBits == 2 ? 2 : dictIdBits == 3 ? 4 : 0;
        int fcsBits = (fhd >> 6) & 3;
        int fcsSize = fcsBits == 0 ? (singleSegment ? 1 : 0) : fcsBits == 1 ? 2 : fcsBits == 2 ? 4 : 8;
        int windowDescSize = singleSegment ? 0 : 1;
        return size + windowDescSize + dictIdSize + fcsSize;
    }
}
