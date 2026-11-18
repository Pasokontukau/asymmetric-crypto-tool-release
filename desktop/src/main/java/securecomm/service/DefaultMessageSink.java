package securecomm.service;

import core.util.Messages;

/** 适配现有 {@link Messages} 资源表。 */
public class DefaultMessageSink implements MessageSink {

    @Override
    public String get(String key, Object... args) {
        if (key == null) {
            return "";
        }
        if (args == null || args.length == 0) {
            return Messages.get(key);
        }
        return Messages.format(key, args);
    }
}
