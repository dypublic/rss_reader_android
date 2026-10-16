package com.codex.rssreader

import androidx.work.WorkInfo
import com.codex.rssreader.diagnostics.pendingReasonLabel
import com.codex.rssreader.diagnostics.standbyBucketLabel
import com.codex.rssreader.diagnostics.stopReasonLabel
import org.junit.Assert.*
import org.junit.Test

class BackgroundDiagnosticsTest {
    @Test fun unknownOemReasonsPreserveTheirCodes() {
        assertEquals("系统原因 (102)", pendingReasonLabel(102))
        assertEquals("未知原因 (102)", stopReasonLabel(102))
        assertEquals("未知", standbyBucketLabel(102))
    }

    @Test fun noStopRecordIsDistinctFromUnknownOrSystemRestriction() {
        assertEquals("无中止记录", stopReasonLabel(WorkInfo.STOP_REASON_NOT_STOPPED))
        assertEquals("原因未知", stopReasonLabel(WorkInfo.STOP_REASON_UNKNOWN))
        assertEquals("后台限制", stopReasonLabel(11))
        assertEquals("豁免", standbyBucketLabel(5))
    }
}
