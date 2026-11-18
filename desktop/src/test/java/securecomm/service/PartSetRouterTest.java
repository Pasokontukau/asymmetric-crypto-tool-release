package securecomm.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import volumes.service.ExternalPartMerger;

/** 路由是纯映射：齐套合并、单卷解密、缺卷补选、其余拒绝。 */
public class PartSetRouterTest {

    @Test
    public void completeSetMerges() {
        assertEquals(PartSetRouter.Route.MERGE,
                PartSetRouter.route(ExternalPartMerger.Status.COMPLETE));
        assertEquals(PartSetRouter.Reason.NONE,
                PartSetRouter.reason(ExternalPartMerger.Status.COMPLETE));
        assertFalse(PartSetRouter.requiresReselection(ExternalPartMerger.Status.COMPLETE));
    }

    @Test
    public void singlePartAndNonSetDecryptAsSingle() {
        assertEquals(PartSetRouter.Route.DECRYPT_SINGLE,
                PartSetRouter.route(ExternalPartMerger.Status.SINGLE_PART));
        assertEquals(PartSetRouter.Route.DECRYPT_SINGLE,
                PartSetRouter.route(ExternalPartMerger.Status.NOT_A_SET));
    }

    @Test
    public void incompleteSetNeedsMoreParts() {
        assertEquals(PartSetRouter.Route.NEED_MORE_PARTS,
                PartSetRouter.route(ExternalPartMerger.Status.INCOMPLETE));
        assertEquals(PartSetRouter.Reason.MISSING_PARTS,
                PartSetRouter.reason(ExternalPartMerger.Status.INCOMPLETE));
        assertTrue(PartSetRouter.requiresReselection(ExternalPartMerger.Status.INCOMPLETE));
    }

    @Test
    public void mixedDuplicateAndInvalidAreRejected() {
        for (ExternalPartMerger.Status status : new ExternalPartMerger.Status[]{
                ExternalPartMerger.Status.MIXED,
                ExternalPartMerger.Status.DUPLICATE,
                ExternalPartMerger.Status.INVALID_INDEX}) {
            assertEquals(status.name(), PartSetRouter.Route.REJECT, PartSetRouter.route(status));
            assertTrue(status.name(), PartSetRouter.requiresReselection(status));
        }
        assertEquals(PartSetRouter.Reason.MIXED_SETS,
                PartSetRouter.reason(ExternalPartMerger.Status.MIXED));
        assertEquals(PartSetRouter.Reason.DUPLICATE_VOLUMES,
                PartSetRouter.reason(ExternalPartMerger.Status.DUPLICATE));
        assertEquals(PartSetRouter.Reason.INVALID_VOLUME_NUMBER,
                PartSetRouter.reason(ExternalPartMerger.Status.INVALID_INDEX));
    }

    @Test
    public void nullStatusIsRejected() {
        assertEquals(PartSetRouter.Route.REJECT, PartSetRouter.route(null));
        assertTrue(PartSetRouter.requiresReselection(null));
    }
}
