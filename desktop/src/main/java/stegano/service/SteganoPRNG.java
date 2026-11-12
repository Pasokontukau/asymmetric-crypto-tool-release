package stegano.service;

import java.security.MessageDigest;
import stegano.service.SteganoException;

public class SteganoPRNG {

    /**
     * 基于种子的确定性随机数生成器。
     * 使用 SHA-256(seed || counter) 生成 64-bit 随机数。
     */
    private static class DeterministicRandom {
        private final MessageDigest md;
        private final byte[] seed;
        private long counter;

        DeterministicRandom(byte[] seed) {
            try {
                this.md = MessageDigest.getInstance("SHA-256");
            } catch (Exception e) {
                throw new SteganoException("SHA-256 algorithm not available", e);
            }
            this.seed = seed.clone();
            this.counter = 0;
        }

        /** 生成 [0, bound) 范围内的随机整数 */
        int nextInt(int bound) {
            if (bound <= 0) return 0;

            long r = nextLong() & Long.MAX_VALUE;
            // 使用取模而非 nextInt 的拒绝采样，简单且对于我们的用途足够均匀
            return (int) (r % bound);
        }

        private long nextLong() {
            md.update(seed);
            // 将 counter 作为 8 字节大端序追加
            byte[] counterBytes = new byte[8];
            long c = counter++;
            for (int i = 7; i >= 0; i--) {
                counterBytes[i] = (byte) (c & 0xFF);
                c >>= 8;
            }
            md.update(counterBytes);
            byte[] hash = md.digest();
            long value = 0;
            for (int i = 0; i < 8; i++) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            return value;
        }
    }

    /**
     * 生成 Fisher-Yates 洗牌后的嵌入单元访问顺序。
     * 从 totalUnits 中随机选择 embedCount 个不重复的位置。
     */
    public static int[] generateEmbedOrder(int totalPixels, int channels,
                                           double embedRate, byte[] seed) {
        int totalUnits = totalPixels * channels;
        int embedCount = (int) (totalUnits * embedRate);

        if (embedCount == 0) {
            return new int[0];
        }

        // 对于较大 embedCount，使用全排列 Fisher-Yates
        // 对于较小 embedCount，使用水库抽样
        if (embedCount > totalUnits / 4) {
            return fisherYatesSample(totalUnits, embedCount, seed);
        } else {
            return reservoirSample(totalUnits, embedCount, seed);
        }
    }

    /**
     * 全排列 Fisher-Yates 洗牌，取前 embedCount 个。
     */
    private static int[] fisherYatesSample(int totalUnits, int embedCount, byte[] seed) {
        int[] order = new int[totalUnits];
        for (int i = 0; i < totalUnits; i++) {
            order[i] = i;
        }

        DeterministicRandom prng = new DeterministicRandom(seed);
        for (int i = totalUnits - 1; i > 0; i--) {
            int j = prng.nextInt(i + 1);
            int temp = order[i];
            order[i] = order[j];
            order[j] = temp;
        }

        int[] result = new int[embedCount];
        System.arraycopy(order, 0, result, 0, embedCount);
        return result;
    }

    /**
     * 水库抽样，适用于 embedCount 远小于 totalUnits 时。
     * 内存占用 O(embedCount)。
     */
    private static int[] reservoirSample(int totalUnits, int embedCount, byte[] seed) {
        DeterministicRandom prng = new DeterministicRandom(seed);

        int[] reservoir = new int[embedCount];
        for (int i = 0; i < embedCount; i++) {
            reservoir[i] = i;
        }

        // Initial shuffle within reservoir
        for (int i = embedCount - 1; i > 0; i--) {
            int j = prng.nextInt(i + 1);
            int temp = reservoir[i];
            reservoir[i] = reservoir[j];
            reservoir[j] = temp;
        }

        // Reservoir sampling
        for (int n = embedCount; n < totalUnits; n++) {
            int r = prng.nextInt(n + 1);
            if (r < embedCount) {
                reservoir[r] = n;
            }
        }

        // Final shuffle
        for (int i = embedCount - 1; i > 0; i--) {
            int j = prng.nextInt(i + 1);
            int temp = reservoir[i];
            reservoir[i] = reservoir[j];
            reservoir[j] = temp;
        }

        return reservoir;
    }
}