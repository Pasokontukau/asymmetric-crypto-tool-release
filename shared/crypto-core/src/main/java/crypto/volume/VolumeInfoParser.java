package crypto.volume;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 严格解析 VOLU 的 VolumeInfo JSON。
 *
 * <p>当前只有一种 VOLU 形态：每卷 {@code volumeCount=1}，且必须携带完整的
 * {@code setId/partIndex/partCount/archive*} 外部分卷字段。旧内部 VOLU 的
 * {@code volumeCount=N}、缺少外部分卷字段或缺少 {@code encryptedVolumeSizes}
 * 的元数据一律拒绝。</p>
 *
 * <p>元数据虽然被 RSA 加密，但接收方公钥是公开的，攻击者可以重新加密任意 JSON，
 * 因此这里按不可信输入处理：字段类型、重复字段、长度、范围、卷数一致性都必须校验。
 * 使用 Gson 的 {@link JsonReader} 流式解析，避免先构造宽松的 JsonObject。</p>
 */
public final class VolumeInfoParser {

    public static final int MAX_JSON_CHARS = 1024 * 1024;
    public static final int MAX_JSON_DEPTH = 8;

    private static final long MAX_PART_PLAINTEXT = Integer.MAX_VALUE - 16L;
    private static final Pattern SHA256_PATTERN = Pattern.compile("^[0-9a-fA-F]{64}$");

    private VolumeInfoParser() {
    }

    public static VolumeInfo parse(String json) throws VolumeFormatException {
        if (json == null || json.isEmpty()) {
            throw new VolumeFormatException("分卷元数据为空");
        }
        if (json.length() > MAX_JSON_CHARS) {
            throw new VolumeFormatException("分卷元数据过大");
        }
        checkDepth(json);

        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            VolumeInfo info = readObject(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new VolumeFormatException("分卷元数据末尾有多余内容");
            }
            return info;
        } catch (VolumeFormatException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new VolumeFormatException("分卷元数据 JSON 无效", error);
        }
    }

    private static VolumeInfo readObject(JsonReader reader) throws IOException, VolumeFormatException {
        expect(reader, JsonToken.BEGIN_OBJECT, "根对象");
        reader.beginObject();

        Set<String> seen = new HashSet<String>();
        String originalFileName = null;
        Long originalFileSize = null;
        Integer volumeCount = null;
        Long volumeSize = null;
        Boolean sourceIsFolder = null;
        String sha256Hash = null;
        String createdAt = null;
        List<Long> encryptedVolumeSizes = null;
        String setId = null;
        Integer partIndex = null;
        Integer partCount = null;
        String archiveName = null;
        Long archiveSize = null;
        String archiveSha256 = null;
        Boolean archiveSourceIsFolder = null;
        Boolean sourceIsArchive = null;
        Integer sourceFileCount = null;

        while (reader.hasNext()) {
            String name = reader.nextName();
            if (!seen.add(name)) {
                throw new VolumeFormatException("分卷元数据字段重复: " + name);
            }
            if ("originalFileName".equals(name)) {
                originalFileName = readString(reader, name, VolumeInfo.MAX_FILE_NAME_CHARS);
            } else if ("originalFileSize".equals(name)) {
                originalFileSize = readLong(reader, name);
            } else if ("volumeCount".equals(name)) {
                volumeCount = readInt(reader, name);
            } else if ("volumeSize".equals(name)) {
                volumeSize = readLong(reader, name);
            } else if ("sourceIsFolder".equals(name)) {
                expect(reader, JsonToken.BOOLEAN, name);
                sourceIsFolder = reader.nextBoolean();
            } else if ("sha256Hash".equals(name)) {
                sha256Hash = readString(reader, name, 64);
            } else if ("createdAt".equals(name)) {
                createdAt = readString(reader, name, VolumeInfo.MAX_CREATED_AT_CHARS);
            } else if ("encryptedVolumeSizes".equals(name)) {
                encryptedVolumeSizes = readLongArray(reader, name);
            } else if ("setId".equals(name)) {
                setId = readString(reader, name, VolumeInfo.MAX_SET_ID_CHARS);
            } else if ("partIndex".equals(name)) {
                partIndex = readInt(reader, name);
            } else if ("partCount".equals(name)) {
                partCount = readInt(reader, name);
            } else if ("archiveName".equals(name)) {
                archiveName = readString(reader, name, VolumeInfo.MAX_FILE_NAME_CHARS);
            } else if ("archiveSize".equals(name)) {
                archiveSize = readLong(reader, name);
            } else if ("archiveSha256".equals(name)) {
                archiveSha256 = readString(reader, name, 64);
            } else if ("archiveSourceIsFolder".equals(name)) {
                expect(reader, JsonToken.BOOLEAN, name);
                archiveSourceIsFolder = reader.nextBoolean();
            } else if ("sourceIsArchive".equals(name)) {
                expect(reader, JsonToken.BOOLEAN, name);
                sourceIsArchive = reader.nextBoolean();
            } else if ("sourceFileCount".equals(name)) {
                sourceFileCount = readInt(reader, name);
            } else {
                // 未知字段跳过；改变已有字段语义必须先升容器版本。
                reader.skipValue();
            }
        }
        reader.endObject();

        if (originalFileName == null) {
            throw new VolumeFormatException("分卷元数据缺少 originalFileName");
        }
        if (originalFileSize == null) {
            throw new VolumeFormatException("分卷元数据缺少 originalFileSize");
        }
        if (volumeCount == null) {
            throw new VolumeFormatException("分卷元数据缺少 volumeCount");
        }
        if (volumeSize == null) {
            throw new VolumeFormatException("分卷元数据缺少 volumeSize");
        }
        if (sourceIsFolder == null) {
            throw new VolumeFormatException("分卷元数据缺少 sourceIsFolder");
        }
        if (encryptedVolumeSizes == null) {
            throw new VolumeFormatException("分卷元数据缺少 encryptedVolumeSizes");
        }
        if (setId == null
                || partIndex == null
                || partCount == null
                || archiveName == null
                || archiveSize == null
                || archiveSha256 == null
                || archiveSourceIsFolder == null
                || sourceIsArchive == null
                || sourceFileCount == null) {
            throw new VolumeFormatException("不是当前 VOLU 外部分卷：缺少集合字段");
        }
        if (sha256Hash != null && sha256Hash.isEmpty()) {
            sha256Hash = null;
        }
        if (sha256Hash != null && !SHA256_PATTERN.matcher(sha256Hash).matches()) {
            throw new VolumeFormatException("分卷元数据 SHA-256 格式不正确");
        }
        validateFileName(originalFileName);
        validateRanges(originalFileSize, volumeCount, volumeSize, encryptedVolumeSizes);
        validateExternalPartFields(
                setId,
                partIndex,
                partCount,
                archiveName,
                archiveSize,
                archiveSha256,
                archiveSourceIsFolder,
                sourceIsArchive,
                sourceFileCount,
                originalFileSize,
                sourceIsFolder
        );

        return new VolumeInfo(
                originalFileName,
                originalFileSize,
                volumeCount,
                volumeSize,
                sourceIsFolder,
                sha256Hash,
                createdAt,
                encryptedVolumeSizes,
                setId,
                partIndex,
                partCount,
                archiveName,
                archiveSize,
                archiveSha256,
                archiveSourceIsFolder,
                sourceIsArchive,
                sourceFileCount
        );
    }

    private static String readString(JsonReader reader, String field, int maxChars)
            throws IOException, VolumeFormatException {
        expect(reader, JsonToken.STRING, field);
        String value = reader.nextString();
        if (value.length() > maxChars) {
            throw new VolumeFormatException("分卷元数据字段过长: " + field);
        }
        return value;
    }

    private static long readLong(JsonReader reader, String field)
            throws IOException, VolumeFormatException {
        expect(reader, JsonToken.NUMBER, field);
        try {
            return reader.nextLong();
        } catch (NumberFormatException error) {
            throw new VolumeFormatException("分卷元数据字段不是整数: " + field, error);
        }
    }

    private static int readInt(JsonReader reader, String field)
            throws IOException, VolumeFormatException {
        long value = readLong(reader, field);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new VolumeFormatException("分卷元数据字段超出 int 范围: " + field);
        }
        return (int) value;
    }

    private static List<Long> readLongArray(JsonReader reader, String field)
            throws IOException, VolumeFormatException {
        if (reader.peek() == JsonToken.NULL) {
            reader.nextNull();
            return null;
        }
        expect(reader, JsonToken.BEGIN_ARRAY, field);
        reader.beginArray();
        List<Long> values = new ArrayList<Long>();
        while (reader.hasNext()) {
            if (values.size() >= VolumeInfo.MAX_EXTERNAL_PARTS) {
                throw new VolumeFormatException("分卷元数据加密分卷大小数组过大");
            }
            values.add(readLong(reader, field));
        }
        reader.endArray();
        return values;
    }

    private static void validateFileName(String name) throws VolumeFormatException {
        if (name.isEmpty()) {
            throw new VolumeFormatException("分卷元数据 originalFileName 为空");
        }
        for (int index = 0; index < name.length(); index++) {
            if (Character.isISOControl(name.charAt(index))) {
                throw new VolumeFormatException("分卷元数据 originalFileName 含控制字符");
            }
        }
    }

    private static void validateExternalPartFields(
            String setId,
            int partIndex,
            int partCount,
            String archiveName,
            long archiveSize,
            String archiveSha256,
            boolean archiveSourceIsFolder,
            boolean sourceIsArchive,
            int sourceFileCount,
            long originalFileSize,
            boolean sourceIsFolder
    ) throws VolumeFormatException {
        if (partCount < 1 || partCount > VolumeInfo.MAX_EXTERNAL_PARTS) {
            throw new VolumeFormatException("外部分卷 partCount 超出范围");
        }
        if (partIndex < 1 || partIndex > partCount) {
            throw new VolumeFormatException("外部分卷 partIndex 超出范围");
        }
        if (setId.isEmpty() || setId.length() > VolumeInfo.MAX_SET_ID_CHARS) {
            throw new VolumeFormatException("外部分卷 setId 为空或过长");
        }
        for (int index = 0; index < setId.length(); index++) {
            if (Character.isISOControl(setId.charAt(index))) {
                throw new VolumeFormatException("外部分卷 setId 含控制字符");
            }
        }
        validateArchiveName(archiveName);
        if (archiveSize <= 0) {
            throw new VolumeFormatException("外部分卷 archiveSize 必须大于 0");
        }
        if (archiveSize < originalFileSize) {
            throw new VolumeFormatException("外部分卷 archiveSize 小于当前卷明文大小");
        }
        if (partCount == 1 && archiveSize != originalFileSize) {
            throw new VolumeFormatException("单卷包的 archiveSize 必须等于当前卷明文大小");
        }
        if (!SHA256_PATTERN.matcher(archiveSha256).matches()) {
            throw new VolumeFormatException("外部分卷 archiveSha256 格式不正确");
        }
        if (sourceFileCount < 0 || sourceFileCount > VolumeInfo.MAX_SOURCE_FILE_COUNT) {
            throw new VolumeFormatException("外部分卷 sourceFileCount 超出范围");
        }
        if (partCount > 1 && sourceIsFolder) {
            throw new VolumeFormatException("多卷包的 sourceIsFolder 必须为 false");
        }
        if (!sourceIsArchive) {
            if (sourceFileCount != 1) {
                throw new VolumeFormatException(
                        "单文件外部分卷的 sourceFileCount 必须为 1");
            }
            if (archiveSourceIsFolder || sourceIsFolder) {
                throw new VolumeFormatException(
                        "单文件外部分卷不能标记为文件夹来源");
            }
        } else if (partCount == 1 && sourceIsFolder && !archiveSourceIsFolder) {
            throw new VolumeFormatException(
                    "单卷包 sourceIsFolder=true 时 archiveSourceIsFolder 也必须为 true");
        }
    }

    private static void validateArchiveName(String name) throws VolumeFormatException {
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new VolumeFormatException("外部分卷 archiveName 无效");
        }
        if (name.length() > VolumeInfo.MAX_FILE_NAME_CHARS) {
            throw new VolumeFormatException("外部分卷 archiveName 过长");
        }
        for (int index = 0; index < name.length(); index++) {
            char value = name.charAt(index);
            if (Character.isISOControl(value) || value == '/' || value == '\\') {
                throw new VolumeFormatException("外部分卷 archiveName 含非法字符");
            }
        }
    }

    private static void validateRanges(
            long originalFileSize,
            int volumeCount,
            long volumeSize,
            List<Long> encryptedVolumeSizes
    ) throws VolumeFormatException {
        if (originalFileSize <= 0) {
            throw new VolumeFormatException("分卷元数据 originalFileSize 必须大于 0");
        }
        if (volumeCount != 1) {
            throw new VolumeFormatException(
                    "不是当前 VOLU 外部分卷：volumeCount 必须为 1");
        }
        if (volumeSize <= 0 || volumeSize > MAX_PART_PLAINTEXT) {
            throw new VolumeFormatException("分卷元数据 volumeSize 超出范围");
        }
        if (volumeSize != originalFileSize) {
            throw new VolumeFormatException(
                    "外部分卷 volumeSize 必须等于当前卷明文大小");
        }
        if (encryptedVolumeSizes == null || encryptedVolumeSizes.size() != 1) {
            throw new VolumeFormatException(
                    "外部分卷必须携带一个 encryptedVolumeSizes");
        }
        Long encryptedSize = encryptedVolumeSizes.get(0);
        if (encryptedSize == null
                || encryptedSize <= 0
                || encryptedSize > Integer.MAX_VALUE) {
            throw new VolumeFormatException("分卷元数据加密分卷大小超出范围");
        }
    }

    private static void expect(JsonReader reader, JsonToken expected, String field)
            throws IOException, VolumeFormatException {
        if (reader.peek() != expected) {
            throw new VolumeFormatException("分卷元数据字段类型不正确: " + field);
        }
    }

    /**
     * 在交给 Gson 前限制大括号/中括号深度，避免恶意深嵌套造成栈/CPU 问题。
     * 这里只做括号状态扫描，不试图替代 JSON 语法校验。
     */
    private static void checkDepth(String json) throws VolumeFormatException {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < json.length(); index++) {
            char c = json.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }
            if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
                if (depth > MAX_JSON_DEPTH) {
                    throw new VolumeFormatException("分卷元数据 JSON 嵌套过深");
                }
            } else if (c == '}' || c == ']') {
                depth--;
                if (depth < 0) {
                    throw new VolumeFormatException("分卷元数据 JSON 括号不匹配");
                }
            }
        }
        if (inString) {
            throw new VolumeFormatException("分卷元数据 JSON 字符串未闭合");
        }
    }
}
