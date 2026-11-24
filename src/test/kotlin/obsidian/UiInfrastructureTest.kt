package obsidian

import net.kyori.adventure.text.Component
import obsidian.core.ui.ActionBarPriority
import obsidian.core.ui.ActionBarService
import obsidian.core.ui.BossBarService
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.*
import java.util.concurrent.atomic.AtomicBoolean

class UiInfrastructureTest {

    @Test
    fun `ActionBarService resolves highest priority unexpired entry and falls back on expiry`() {
        val service = ActionBarService(context = null)
        val uuid = UUID.randomUUID()
        val now = System.currentTimeMillis()

        service.setState(uuid, "afk", ActionBarPriority.AFK, Component.text("AFK"))
        service.setState(uuid, "vanish", ActionBarPriority.VANISH, Component.text("VANISH"))
        service.setState(uuid, "combat", ActionBarPriority.COMBAT, Component.text("COMBAT"))
        service.setState(
            uuid,
            ActionBarService.TRANSIENT_KEY,
            ActionBarPriority.TRANSIENT,
            Component.text("SAVED"),
            ttlMillis = 500L
        )

        // Transient wins while active
        assertEquals(ActionBarPriority.TRANSIENT, service.resolveActiveEntry(uuid, now)?.priority)

        // After transient expires, COMBAT wins
        assertEquals(ActionBarPriority.COMBAT, service.resolveActiveEntry(uuid, now + 1000L)?.priority)

        // When combat is cleared, VANISH wins
        service.clearState(uuid, "combat")
        assertEquals(ActionBarPriority.VANISH, service.resolveActiveEntry(uuid, now + 1000L)?.priority)

        // When low-priority HUD is disabled, VANISH and AFK are suppressed
        assertNull(service.resolveActiveEntry(uuid, now + 1000L, includeLowPriorityHud = false))
    }

    @Test
    fun `BossBarService global countdown decrements and fires completion callback`() {
        val service = BossBarService(context = null)
        val completed = AtomicBoolean(false)
        val seenRemaining = mutableListOf<Int>()

        service.showGlobalCountdown(
            id = "test_countdown",
            durationSeconds = 3,
            titleProvider = { rem ->
                seenRemaining += rem
                Component.text("Remaining: $rem")
            },
            onComplete = { completed.set(true) },
        )

        assertTrue(service.hasGlobalBar("test_countdown"))
        service.tick() // 3 -> 2
        assertFalse(completed.get())
        assertTrue(service.hasGlobalBar("test_countdown"))

        service.tick() // 2 -> 1
        assertFalse(completed.get())

        service.tick() // 1 -> 0 (completes)
        assertTrue(completed.get())
        assertFalse(service.hasGlobalBar("test_countdown"))
        assertEquals(listOf(3, 2, 1), seenRemaining)
    }
}
