package core.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;

import java.io.InputStream;
import org.junit.Test;

/** 中文资源必须打进 classpath，不能再依赖启动目录。 */
public class MessagesTest {

    @Test
    public void resourceIsOnClasspath() {
        try (InputStream stream = Messages.class.getResourceAsStream("/messages.properties")) {
            assertNotNull("messages.properties 应从 src/main/resources 进入 classpath", stream);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    public void titleIsLocalizedInsteadOfKey() {
        assertNotEquals("app.title", Messages.get("app.title"));
        assertEquals("非对称加密工具", Messages.get("app.title"));
    }

    @Test
    public void unknownKeyFallsBackToItself() {
        assertEquals("missing.key.for.test", Messages.get("missing.key.for.test"));
    }
}
