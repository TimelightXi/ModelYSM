package ui.modelysm.client.ysm.crypto;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * YSM 文件解密（从 OpenYSM rip.ysm.security.YsmCrypt 移植，仅保留 .ysm 文件解密所需部分）。
 * 文件结构：UTF-8 文本元数据头（0x00 结束）+ crypto 版本 int32(LE) + 加密负载
 *           + 文件尾 64 字节（key 32 + iv 24 + CityHash 签名 8）。
 * 解密链：分块 XChaCha20（块大小/轮数由 keyIv 的 CityHash 派生）→ MT19937 XOR → 跳过 padding → zstd 解压。
 */
public final class YsmCrypt {
    private YsmCrypt() {}

    public static final long SEED_KEY_DERIVATION = 0xD017CBBA7B5D3581L;
    public static final long SEED_FILE_VERIFICATION = 0x9E5599DB80C67C29L;
    public static final long SEED_RES_VERIFICATION = 0xA62B1A2C43842BC3L;

    public static byte[] decryptYsmFile(byte[] fileData) throws Exception {
        if (fileData.length < 8 + 24 + 32 + 8) {
            throw new RuntimeException("Invalid YSM file: File too short.");
        }

        int headerLength = 0;
        while (headerLength < fileData.length && fileData[headerLength] != 0x00) {
            headerLength++;
        }

        int tailOffset = fileData.length - 64;
        byte[] key = new byte[32];
        System.arraycopy(fileData, tailOffset, key, 0, 32);
        byte[] iv = new byte[24];
        System.arraycopy(fileData, tailOffset + 32, iv, 0, 24);
        long fileHash = ByteBuffer.wrap(fileData, tailOffset + 56, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();

        CityHash ch = new CityHash();
        byte[] withoutTail = new byte[fileData.length - 8];
        System.arraycopy(fileData, 0, withoutTail, 0, fileData.length - 8);
        long calculatedHash = ch.hash64WithSeed(withoutTail, SEED_FILE_VERIFICATION);
        if (calculatedHash != fileHash) {
            throw new RuntimeException("Corrupted YSM file: File hash mismatch.");
        }

        int ptrBinaryData = headerLength + 1;
        int crypto = ByteBuffer.wrap(fileData, ptrBinaryData, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (crypto != 3) {
            throw new RuntimeException("Invalid YSM file: Crypto version is not 3 (got " + crypto + ").");
        }
        ptrBinaryData += 4;

        byte[] encryptedBinaryData = new byte[tailOffset - ptrBinaryData];
        System.arraycopy(fileData, ptrBinaryData, encryptedBinaryData, 0, encryptedBinaryData.length);
        byte[] chachaDecrypted = modifiedChaChaDecrypt(encryptedBinaryData, key, iv, SEED_RES_VERIFICATION);

        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);
        byte[] xorredData = mt19937Xor(chachaDecrypted, keyIv, SEED_KEY_DERIVATION);

        // 头部 2 字节：padding 长度（低 10 位）
        int n = ((xorredData[0] & 0xFF) | ((xorredData[1] & 0xFF) << 8)) & 0x3FF;
        int zstdOffset = 2 + n;
        byte[] bytes = new byte[xorredData.length - zstdOffset];
        System.arraycopy(xorredData, zstdOffset, bytes, 0, bytes.length);

        return YsmZstd.decompress(bytes);
    }

    private static byte[] modifiedChaChaDecrypt(byte[] data, byte[] key, byte[] iv, long seed) throws Exception {
        byte[] keyIv = new byte[56];
        System.arraycopy(key, 0, keyIv, 0, 32);
        System.arraycopy(iv, 0, keyIv, 32, 24);

        CityHash ch = new CityHash();
        long hash2 = ch.hash64WithSeed(keyIv, seed);

        int nextRoundSize = (int) (((hash2 & 0x3FL) | 0x40L) << 6);
        int rounds = (int) (10 * Long.remainderUnsigned(hash2, 3) + 10);

        XChaCha20 ctx = new XChaCha20(key, iv, rounds);

        byte[] result = new byte[data.length];
        int blockPointer = 0;
        while (blockPointer < data.length) {
            if (blockPointer + nextRoundSize > data.length) {
                nextRoundSize = data.length - blockPointer;
            }
            byte[] decChunk = new byte[nextRoundSize];
            System.arraycopy(data, blockPointer, decChunk, 0, nextRoundSize);
            decChunk = ctx.processBytes(decChunk, 0, nextRoundSize);
            System.arraycopy(decChunk, 0, result, blockPointer, nextRoundSize);
            blockPointer += nextRoundSize;
            if (blockPointer < data.length) {
                long resHash = ch.hash64WithSeed(decChunk, seed);
                nextRoundSize = ctx.updateStateYSM(resHash);
            }
        }
        return result;
    }

    private static byte[] mt19937Xor(byte[] data, byte[] currentKeyIv, long seedDerivation) {
        long mtSeed = new CityHash().hash64WithSeed(currentKeyIv, seedDerivation);
        MT19937 mt = new MT19937(mtSeed);
        byte[] result = new byte[data.length];

        int i = 0;
        while (i < data.length) {
            long rnd = mt.extract_number();
            for (int j = 0; j < 8 && i < data.length; ++j) {
                byte keystreamByte = (byte) ((rnd >>> (j * 8)) & 0xFF);
                result[i] = (byte) (data[i] ^ keystreamByte);
                i++;
            }
        }
        return result;
    }
}
