package history.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import core.util.ExportCrypto;
import history.model.HistoryRecord;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.util.List;

/**
 * 历史记录导出。明文格式只用于用户明确选择的 JSON/CSV/HTML；
 * 默认导出走 {@link #writeEncrypted}，避免在磁盘上留下文件名、路径和联系人。
 */
public final class HistoryExport {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private HistoryExport() {
    }

    public static void writeEncrypted(File target, List<HistoryRecord> records, char[] password)
            throws IOException, GeneralSecurityException {
        byte[] plaintext = toJson(records).getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = ExportCrypto.encrypt(plaintext, password);
        Files.write(target.toPath(), encrypted);
    }

    public static String toJson(List<HistoryRecord> records) {
        return GSON.toJson(records);
    }

    public static String toCsv(List<HistoryRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("ID,时间,类型,描述,结果,联系人,分类,密钥对\n");
        for (HistoryRecord record : records) {
            sb.append(String.format(
                    "%s,%s,%s,%s,%s,%s,%s,%s\n",
                    escapeCsv(record.getId()),
                    escapeCsv(record.getTimestamp()),
                    escapeCsv(record.getType().getDisplayName()),
                    escapeCsv(record.getDescription()),
                    escapeCsv(record.getResult()),
                    escapeCsv(record.getContactName()),
                    escapeCsv(record.getCategory().getDisplayName()),
                    escapeCsv(record.getKeyPairName())
            ));
        }
        return sb.toString();
    }

    public static String toHtml(List<HistoryRecord> records) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><meta charset=\"UTF-8\"><title>历史记录</title>");
        sb.append("<style>table{border-collapse:collapse;width:100%}th,td{border:1px solid #ddd;padding:8px;text-align:left}th{background:#f2f2f2}.success{color:green}.failure{color:red}</style>");
        sb.append("</head><body>");
        sb.append("<h1>历史记录</h1>");
        sb.append("<table><tr><th>时间</th><th>类型</th><th>描述</th><th>结果</th><th>联系人</th><th>分类</th></tr>");
        for (HistoryRecord record : records) {
            sb.append(String.format(
                    "<tr><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td><td>%s</td></tr>\n",
                    escapeHtml(record.getTimestamp()),
                    escapeHtml(record.getType().getDisplayName()),
                    escapeHtml(record.getDescription()),
                    record.isSuccess()
                            ? escapeHtml(record.getResult())
                            : "<span class='failure'>" + escapeHtml(record.getResult()) + "</span>",
                    escapeHtml(record.getContactName()),
                    escapeHtml(record.getCategory().getDisplayName())
            ));
        }
        sb.append("</table></body></html>");
        return sb.toString();
    }

    private static String escapeCsv(String value) {
        if (value == null) {
            return "";
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
