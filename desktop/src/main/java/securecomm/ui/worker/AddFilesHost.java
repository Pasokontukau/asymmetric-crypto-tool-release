package securecomm.ui.worker;

import java.io.File;

/**
 * 添加文件 Worker 与面板之间的窄接口（DR-2 步骤 2.5）：
 * Worker 只负责筛重、打包文件夹与汇报进度，列表与状态栏由面板更新。
 */
public interface AddFilesHost {

    /** 该文件是否已经在输入列表里（去重口径与面板一致）。 */
    boolean isAlreadySelected(File file);

    /** 把一个处理好的文件加入输入列表（既加数据模型也加界面模型）。 */
    void addSelectedFile(File file);

    /** 更新结果/状态标签；{@code error=true} 时用错误色。 */
    void showStatus(String text, boolean error);

    /** 收尾：有新增时切到文件模式并显示“成功添加 N 个文件”，最后刷新保存按钮。 */
    void finishAdd(int addedCount);
}
