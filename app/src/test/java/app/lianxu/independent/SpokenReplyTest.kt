package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class SpokenReplyTest {
    @Test fun firstAcknowledgementIsKeptVerbatim(){assertEquals("已说原话",SpokenReply.merge("","已说原话"))}
    @Test fun nextBubbleAppends(){assertEquals("一\n\n二",SpokenReply.merge("一","一\n\n二"))}
    @Test fun oldSaveArrivingLateCannotTruncate(){assertEquals("一\n\n二",SpokenReply.merge("一\n\n二","一"))}
    @Test fun emptyInterruptedSnapshotCannotEraseDurablePrefix(){assertEquals("已说",SpokenReply.merge("已说",""))}
    @Test fun retryingTheSameSaveIsIdempotent(){assertEquals("已说",SpokenReply.merge("已说","已说"))}
    @Test(expected=IllegalStateException::class) fun replacementOfPublishedWordsIsRejected(){SpokenReply.merge("已说","改成别的话")}
}
