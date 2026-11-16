package app.lianxu.independent

import org.junit.Assert.*
import org.junit.Test

class FirstEncounterStateTest {
    private fun settled()=FirstEncounterState().start().doorOpened().entered()

    @Test fun unloadedEmptySnapshotNeverCountsAsAFirstVisit(){
        assertFalse(FirstEncounterState().shouldShow(loaded=false,hasRoles=false,page="home"))
    }
    @Test fun loadedEmptyHomeOffersTheDoor(){
        assertTrue(FirstEncounterState().shouldShow(loaded=true,hasRoles=false,page="home"))
    }
    @Test fun existingRolesBypassEveryPhase(){
        FirstEncounterPhase.entries.forEach{phase->
            assertFalse(FirstEncounterState(phase).shouldShow(loaded=true,hasRoles=true,page="home"))
        }
    }
    @Test fun existingPagesAreNeverReplacedByTheDoor(){
        for(page in listOf("create","role-archive","chat","settings-updates","models")){
            assertFalse(FirstEncounterState().shouldShow(loaded=true,hasRoles=false,page=page))
        }
    }
    @Test fun openingThenEnteringMustSettleBeforeActionsBecomeAvailable(){
        val closed=FirstEncounterState();assertFalse(closed.canChoose)
        val opening=closed.start();assertEquals(FirstEncounterPhase.OPENING,opening.phase);assertFalse(opening.canChoose)
        val entering=opening.doorOpened();assertEquals(FirstEncounterPhase.ENTERING,entering.phase);assertFalse(entering.canChoose)
        val ready=entering.entered();assertEquals(FirstEncounterPhase.READY,ready.phase);assertTrue(ready.canChoose)
    }
    @Test fun repeatedTapsCannotRestartOrSkipAnAnimation(){
        for(state in listOf(FirstEncounterState().start(),FirstEncounterState().start().doorOpened(),settled())){
            var current=state;repeat(30){current=current.start()};assertEquals(state,current)
        }
    }
    @Test fun outOfOrderAndRepeatedCompletionCallbacksDoNotSkipStages(){
        val closed=FirstEncounterState();assertEquals(closed,closed.entered());assertEquals(closed,closed.doorOpened())
        val opening=closed.start();assertEquals(opening,opening.entered())
        val entering=opening.doorOpened();assertEquals(entering,entering.doorOpened())
        val ready=entering.entered();assertEquals(ready,ready.doorOpened());assertEquals(ready,ready.entered())
    }
    @Test fun neitherDestinationCanNavigateBeforeTheViewSettles(){
        for(state in listOf(FirstEncounterState(),FirstEncounterState().start(),FirstEncounterState().start().doorOpened())){
            FirstEncounterDestination.entries.forEach{target->
                val choice=state.choose(target);assertEquals(state,choice.state);assertNull(choice.navigateTo)
            }
        }
    }
    @Test fun eachDestinationUsesItsExistingPageAndEmitsOnlyOneNavigation(){
        assertEquals("create",FirstEncounterDestination.CREATE.page)
        assertEquals("role-archive",FirstEncounterDestination.RESTORE.page)
        FirstEncounterDestination.entries.forEach{target->
            val choice=settled().choose(target)
            assertEquals(target,choice.navigateTo);assertEquals(target,choice.state.destination);assertFalse(choice.state.canChoose)
            var current=choice.state
            repeat(30){
                FirstEncounterDestination.entries.forEach{repeated->
                    val ignored=current.choose(repeated);assertNull(ignored.navigateTo);assertEquals(current,ignored.state);current=ignored.state
                }
            }
            // Keep the door surface until the destination page replaces it; no old-home flash.
            assertTrue(current.shouldShow(loaded=true,hasRoles=false,page="home"))
        }
    }
    @Test fun cancellingCreateOrRestoreKeepsTheSettledViewAndAllowsAnotherChoice(){
        FirstEncounterDestination.entries.forEach{target->
            val returned=settled().choose(target).state.returned()
            assertEquals(settled(),returned);assertTrue(returned.canChoose)
            assertTrue(returned.shouldShow(loaded=true,hasRoles=false,page="home"))
            assertEquals(returned,returned.start())
            assertNotNull(returned.choose(target).navigateTo)
        }
    }
    @Test fun successfulCreateOrRestoreBypassesTheDoorAsSoonAsRolesExist(){
        FirstEncounterDestination.entries.forEach{target->
            val state=settled().choose(target).state.returned()
            assertFalse(state.shouldShow(loaded=true,hasRoles=true,page="home"))
        }
    }
    @Test fun reducedMotionImmediatelyProvidesActionsFromAnyAnimationStage(){
        FirstEncounterPhase.entries.forEach{phase->
            val state=FirstEncounterState(phase).withoutMotion()
            assertEquals(settled(),state);assertTrue(state.canChoose)
            assertEquals(state,state.start().doorOpened().entered().withoutMotion())
        }
    }
    @Test fun changingMotionPreferenceWhileAChildPageIsOpenDoesNotUnlockNavigation(){
        val chosen=settled().choose(FirstEncounterDestination.RESTORE).state
        assertEquals(chosen,chosen.withoutMotion());assertNull(chosen.withoutMotion().choose(FirstEncounterDestination.CREATE).navigateTo)
    }
    @Test fun returningWithoutHavingLeftDoesNotSkipTheDoor(){
        val initial=FirstEncounterState();assertEquals(initial,initial.returned());assertFalse(initial.returned().canChoose)
        val opening=initial.start();assertEquals(opening,opening.returned())
    }
    @Test fun aChildDestinationCannotExistBeforeTheViewSettles(){
        assertTrue(runCatching{FirstEncounterState(FirstEncounterPhase.OPENING,FirstEncounterDestination.CREATE)}.isFailure)
    }
    @Test fun rebuildingDuringEitherTransitionSettlesInsteadOfReplaying(){
        for(state in listOf(FirstEncounterState().start(),FirstEncounterState().start().doorOpened()))assertEquals(settled(),state.restored())
    }
    @Test fun rebuildingPreservesUnstartedAndChildPageStates(){
        val closed=FirstEncounterState();assertEquals(closed,closed.restored())
        FirstEncounterDestination.entries.forEach{target->val chosen=settled().choose(target).state;assertEquals(chosen,chosen.restored())}
    }
}
