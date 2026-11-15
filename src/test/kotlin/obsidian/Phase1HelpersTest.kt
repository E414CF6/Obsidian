package obsidian

import obsidian.modules.home.HomeService
import obsidian.modules.teleport.SafeLocationFinder
import obsidian.modules.utility.UtilityHelpers
import org.bukkit.GameMode
import org.bukkit.Material
import org.bukkit.WeatherType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Phase1HelpersTest {

    @Test
    fun `resolveMaxNumericPermission picks highest numeric suffix above default`() {
        val perms = listOf(
            "obsidian.command.home",
            "obsidian.sethome.5",
            "obsidian.sethome.12",
            "obsidian.sethome.notanumber",
            "other.perm.99",
        )
        assertEquals(12, HomeService.resolveMaxNumericPermission(perms, "obsidian.sethome.", 3))
        assertEquals(15, HomeService.resolveMaxNumericPermission(perms, "obsidian.sethome.", 15))
        assertEquals(3, HomeService.resolveMaxNumericPermission(emptyList(), "obsidian.sethome.", 3))
    }

    @Test
    fun `UtilityHelpers parseGameMode handles short and full aliases`() {
        assertEquals(GameMode.SURVIVAL, UtilityHelpers.parseGameMode("0"))
        assertEquals(GameMode.SURVIVAL, UtilityHelpers.parseGameMode("s"))
        assertEquals(GameMode.CREATIVE, UtilityHelpers.parseGameMode("C"))
        assertEquals(GameMode.ADVENTURE, UtilityHelpers.parseGameMode("adventure"))
        assertEquals(GameMode.SPECTATOR, UtilityHelpers.parseGameMode("sp"))
        assertNull(UtilityHelpers.parseGameMode("unknown"))
    }

    @Test
    fun `UtilityHelpers scaleSpeedToBukkit maps 0 to 10 onto Bukkit speed scale`() {
        assertEquals(0.0f, UtilityHelpers.scaleSpeedToBukkit(0.0, isFlying = false), 1e-5f)
        assertEquals(0.2f, UtilityHelpers.scaleSpeedToBukkit(1.0, isFlying = false), 1e-5f)
        assertEquals(1.0f, UtilityHelpers.scaleSpeedToBukkit(10.0, isFlying = false), 1e-5f)
        assertEquals(0.1f, UtilityHelpers.scaleSpeedToBukkit(1.0, isFlying = true), 1e-5f)
        assertEquals(1.0f, UtilityHelpers.scaleSpeedToBukkit(25.0, isFlying = true), 1e-5f)
    }

    @Test
    fun `UtilityHelpers parsePersonalTime and parsePersonalWeather parse named presets`() {
        assertEquals(1000L, UtilityHelpers.parsePersonalTime("day"))
        assertEquals(6000L, UtilityHelpers.parsePersonalTime("noon"))
        assertEquals(13000L, UtilityHelpers.parsePersonalTime("NIGHT"))
        assertEquals(18000L, UtilityHelpers.parsePersonalTime("midnight"))
        assertEquals(4500L, UtilityHelpers.parsePersonalTime("4500"))
        assertNull(UtilityHelpers.parsePersonalTime("invalid"))

        assertEquals(WeatherType.CLEAR, UtilityHelpers.parsePersonalWeather("sun"))
        assertEquals(WeatherType.DOWNFALL, UtilityHelpers.parsePersonalWeather("rain"))
        assertNull(UtilityHelpers.parsePersonalWeather("fog"))
    }

    @Test
    fun `SafeLocationFinder classifies safe standing columns and hazards`() {
        val solidStubs = setOf(Material.STONE, Material.GRASS_BLOCK, Material.MAGMA_BLOCK)
        val isSolid: (Material) -> Boolean = { it in solidStubs }

        assertTrue(SafeLocationFinder.isHazardous(Material.LAVA))
        assertTrue(SafeLocationFinder.isHazardous(Material.MAGMA_BLOCK))
        assertFalse(SafeLocationFinder.isHazardous(Material.STONE))

        assertTrue(SafeLocationFinder.isSafeFloor(Material.STONE, isSolid))
        assertFalse(SafeLocationFinder.isSafeFloor(Material.MAGMA_BLOCK, isSolid))
        assertFalse(SafeLocationFinder.isSafeFloor(Material.AIR, isSolid))

        assertTrue(SafeLocationFinder.isPassableForBody(Material.AIR, isSolid))
        assertFalse(SafeLocationFinder.isPassableForBody(Material.WATER, isSolid))
        assertFalse(SafeLocationFinder.isPassableForBody(Material.STONE, isSolid))

        assertTrue(SafeLocationFinder.isSafeStandingColumn(Material.GRASS_BLOCK, Material.AIR, Material.AIR, isSolid))
        assertFalse(SafeLocationFinder.isSafeStandingColumn(Material.MAGMA_BLOCK, Material.AIR, Material.AIR, isSolid))
        assertFalse(SafeLocationFinder.isSafeStandingColumn(Material.STONE, Material.STONE, Material.AIR, isSolid))
        assertFalse(SafeLocationFinder.isSafeStandingColumn(Material.STONE, Material.AIR, Material.WATER, isSolid))

        // Surface standing start Y sits one block above highest non-air block, clamped to maxHeight - 2.
        assertEquals(
            -60, SafeLocationFinder.highestStandingSearchStartY(highestBlockY = -61, isNether = false, maxHeight = 320)
        )
        assertEquals(
            318, SafeLocationFinder.highestStandingSearchStartY(highestBlockY = 319, isNether = false, maxHeight = 320)
        )
        assertEquals(
            125, SafeLocationFinder.highestStandingSearchStartY(highestBlockY = 200, isNether = true, maxHeight = 256)
        )
    }
}
