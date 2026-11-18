package securecomm.service;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * 分卷提示的唯一入口：文案与迁移前逐字一致，避免两个调用点各自改动。
 */
public class PartSetPromptTextTest {

    @Test
    public void blockedReasonsKeepOriginalWording() {
        assertEquals("分卷请单独选择，不要和其它文件混在一起。",
                PartSetPromptText.mixedSelection());
        assertEquals("选中的分卷不属于同一个包，请只保留同一套分卷。",
                PartSetPromptText.mixedSets());
        assertEquals("同一卷号被选择了两次，请移除重复文件。",
                PartSetPromptText.duplicateVolumes());
        assertEquals("卷号无效，请重新选择完整分卷。",
                PartSetPromptText.invalidVolumeNumber());
        assertEquals("无法合并选中的分卷。", PartSetPromptText.unmergeable());
    }

    @Test
    public void missingPartsCarriesDetailAndCount() {
        assertEquals("分卷不齐（缺第 2 卷），请补齐后重新解密。",
                PartSetPromptText.missingParts("缺第 2 卷"));
        assertEquals("分卷不完整：还缺 2 卷", PartSetPromptText.missingCount(2));
        assertEquals("请先选择分卷", PartSetPromptText.noPartsSelected());
    }

    @Test
    public void volumeSelectionHintPointsToBatchEntry() {
        assertEquals("多卷外部包请把全部卷一起选中后点「解密并验证」",
                PartSetPromptText.volumeSelectionHint());
    }

    @Test
    public void singleVolumeMessageNamesPositionAndNextStep() {
        assertEquals("这是外部分卷第 2/3 卷，不能单独解密；"
                        + "请把全部 3 卷一起选中后点「解密并验证」，批量解密会自动合并。",
                PartSetPromptText.singleVolumeCannotDecrypt(2, 3));
        assertEquals("卷号缺失时用“未知”占位",
                "这是外部分卷第 未知/3 卷，不能单独解密；"
                        + "请把全部 3 卷一起选中后点「解密并验证」，批量解密会自动合并。",
                PartSetPromptText.singleVolumeCannotDecrypt(null, 3));
    }
}
