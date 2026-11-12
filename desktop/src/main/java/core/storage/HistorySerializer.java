package core.storage;

import history.model.HistoryRecord;
import java.util.List;

public interface HistorySerializer {
    
    /**
     * 将历史记录列表序列化为字符串
     * @param records 历史记录列表
     * @return 序列化后的字符串
     * @throws Exception 序列化失败时抛出
     */
    String serialize(List<HistoryRecord> records) throws Exception;
    
    /**
     * 将字符串反序列化为历史记录列表
     * @param data 序列化的字符串
     * @return 历史记录列表
     * @throws Exception 反序列化失败时抛出
     */
    List<HistoryRecord> deserialize(String data) throws Exception;
}