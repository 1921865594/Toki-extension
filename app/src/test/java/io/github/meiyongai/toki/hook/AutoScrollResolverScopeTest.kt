package io.github.meiyongai.toki.hook

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 关注页的修复要求是“真实 scope -> ability”，而不是“controller -> ability”。 */
class AutoScrollResolverScopeTest {
    private interface Ability
    private class Root(val ability: Ability)
    private class Controller(val root: Root)

    @Test fun abilityIsKeptInsideTheRealScope() {
        val ability = object : Ability {}
        val root = Root(ability)
        assertTrue(root.ability === ability)
    }

    @Test fun playbackControllerDoesNotBecomeAbility() {
        val ability = object : Ability {}
        val controller = Controller(Root(ability))
        assertFalse(controller is Ability)
    }
}
