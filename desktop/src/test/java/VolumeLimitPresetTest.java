import static org.junit.Assert.assertEquals;

import org.junit.Test;
import settings.model.VolumeLimitPreset;

/** 桌面分卷档位的字节值、默认值与持久化解析。 */
public class VolumeLimitPresetTest {

    @Test
    public void defaultsTo500Mb() {
        assertEquals(VolumeLimitPreset.MB_500, VolumeLimitPreset.DEFAULT);
        assertEquals(500_000_000L, VolumeLimitPreset.DEFAULT.getBytes());
        assertEquals("500 MB", VolumeLimitPreset.DEFAULT.getLabel());
    }

    @Test
    public void exposesFiveDecimalPresets() {
        assertEquals(5, VolumeLimitPreset.values().length);
        assertEquals(50_000_000L, VolumeLimitPreset.MB_50.getBytes());
        assertEquals(100_000_000L, VolumeLimitPreset.MB_100.getBytes());
        assertEquals(1_000_000_000L, VolumeLimitPreset.GB_1.getBytes());
        assertEquals(2_000_000_000L, VolumeLimitPreset.GB_2.getBytes());
    }

    @Test
    public void readsBackStoredNames() {
        for (VolumeLimitPreset preset : VolumeLimitPreset.values()) {
            assertEquals(preset, VolumeLimitPreset.fromStored(preset.name()));
        }
    }

    @Test
    public void unknownOrMissingValueFallsBackToDefault() {
        assertEquals(VolumeLimitPreset.DEFAULT, VolumeLimitPreset.fromStored(null));
        assertEquals(VolumeLimitPreset.DEFAULT, VolumeLimitPreset.fromStored(""));
        assertEquals(VolumeLimitPreset.DEFAULT, VolumeLimitPreset.fromStored("MB_200"));
        assertEquals(VolumeLimitPreset.DEFAULT, VolumeLimitPreset.fromStored("500"));
    }
}
