package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class DomainTest {
    private fun roles()=(1..3).map{GeneratedRole(Friend("$it","角色$it","合成简介"),"设定","表达","")}
    @Test fun oldCallbackCannotReplaceNewBatch(){val s=SearchGate();val old=s.begin();val fresh=s.begin();assertTrue(s.complete(fresh,roles()));assertFalse(s.complete(old,roles().reversed()));assertEquals("1",s.candidates.first().friend.id)}
    @Test fun cancelRejectsLateResult(){val s=SearchGate();val id=s.begin();s.cancel();assertFalse(s.complete(id,roles()));assertTrue(s.candidates.isEmpty())}
    @Test fun refreshAndCancelPreserveLastCompleteBatch(){val s=SearchGate();s.complete(s.begin(),roles());val previous=s.candidates;val id=s.begin();assertEquals(previous,s.candidates);s.cancel();assertEquals(previous,s.candidates);assertFalse(s.complete(id,roles().reversed()))}
    @Test(expected=IllegalArgumentException::class) fun rejectPartialBatch(){val s=SearchGate();s.complete(s.begin(),roles().take(2))}
}
