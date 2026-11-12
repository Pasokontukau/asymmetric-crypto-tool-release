package core.util;

import core.util.LogManager;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Properties;

public class Messages {
    
    private static final String BASE_NAME = "messages";
    private static final String EXTENSION = ".properties";
    private static Properties messages;
    private static Locale currentLocale;
    
    static {
        currentLocale = Locale.getDefault();
        loadMessages();
    }
    
    private static String getResourceName(String localeSuffix) {
        String fileName = BASE_NAME;
        if (localeSuffix != null && !localeSuffix.isEmpty()) {
            fileName += "_" + localeSuffix;
        }
        return fileName + EXTENSION;
    }
    
    private static void loadMessages() {
        messages = new Properties();
        
        // 加载顺序：默认 -> 语言 -> 语言_国家
        String[] suffixes = {
            null,
            currentLocale.getLanguage(),
            currentLocale.getLanguage() + "_" + currentLocale.getCountry()
        };
        
        for (String suffix : suffixes) {
            String resource = getResourceName(suffix);
            try (InputStream stream = Messages.class.getResourceAsStream("/" + resource)) {
                if (stream == null) {
                    continue;
                }
                try (Reader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    Properties props = new Properties();
                    props.load(reader);
                    messages.putAll(props);
                }
            } catch (IOException e) {
                LogManager.warning("加载国际化资源文件失败: " + resource, e);
            }
        }
        
        if (messages.isEmpty()) {
            LogManager.warning("未找到任何国际化资源文件，使用默认英文键名");
        }
    }
    
    /**
     * 获取指定键的本地化字符串
     */
    public static String get(String key) {
        if (messages == null || messages.isEmpty()) {
            return key;
        }
        return messages.getProperty(key, key);
    }
    
    /**
     * 获取带参数格式化的本地化字符串
     */
    public static String format(String key, Object... args) {
        String pattern = get(key);
        if (args.length > 0) {
            return MessageFormat.format(pattern, args);
        }
        return pattern;
    }
    
    /**
     * 切换语言
     */
    public static void setLocale(Locale locale) {
        currentLocale = locale;
        loadMessages();
    }
    
    /**
     * 获取当前语言
     */
    public static Locale getCurrentLocale() {
        return currentLocale;
    }
}