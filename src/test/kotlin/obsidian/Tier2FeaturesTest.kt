package obsidian

import net.kyori.adventure.text.format.NamedTextColor
import obsidian.modules.interaction.NameplateService
import obsidian.modules.utility.CombatTagService
import org.bukkit.Material
import org.bukkit.entity.EntityType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class Tier2FeaturesTest {

    @Test
    fun `NameplateService parses NamedTextColor values and reset tokens`() {
        assertEquals(NamedTextColor.AQUA, NameplateService.parseNamedColor("aqua"))
        assertEquals(NamedTextColor.GOLD, NameplateService.parseNamedColor("GOLD"))
        assertNull(NameplateService.parseNamedColor("reset"))
        assertNull(NameplateService.parseNamedColor("clear"))
        assertNull(NameplateService.parseNamedColor("not_a_color"))
    }

    @Test
    fun `CombatTagService maps supported mob head materials cleanly`() {
        assertNull(CombatTagService.createMobHead(EntityType.COW, 1))
        assertEquals(Material.CREEPER_HEAD, Material.valueOf("CREEPER_HEAD"))
        assertEquals(Material.ZOMBIE_HEAD, Material.valueOf("ZOMBIE_HEAD"))
        assertEquals(Material.SKELETON_SKULL, Material.valueOf("SKELETON_SKULL"))
        assertEquals(Material.WITHER_SKELETON_SKULL, Material.valueOf("WITHER_SKELETON_SKULL"))
    }
}
