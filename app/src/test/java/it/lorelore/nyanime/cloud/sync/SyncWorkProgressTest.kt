package it.lorelore.nyanime.cloud.sync

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class SyncWorkProgressTest {
    @Test fun unknownTotalsRemainUnknown() {
        assertNull(SyncWorkProgress(SyncWorkStage.RECEIVING, 40).total)
    }

    @Test fun invalidProgressCannotExceedRealWork() {
        assertThrows(IllegalArgumentException::class.java) { SyncWorkProgress(SyncWorkStage.SENDING, -1) }
        assertThrows(IllegalArgumentException::class.java) { SyncWorkProgress(SyncWorkStage.SENDING, 4, 3) }
    }
}
