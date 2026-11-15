package obsidian.modules.protect

import org.bukkit.Location
import org.bukkit.block.data.BlockData

object ProtectActions {
    const val BLOCK_BREAK = 0
    const val BLOCK_PLACE = 1
    const val BLOCK_INTERACT = 2

    const val CONTAINER_REMOVE = 0
    const val CONTAINER_ADD = 1

    const val ENTITY_KILL = 0
    const val ENTITY_SPAWN = 1

    const val MSG_CHAT = 0
    const val MSG_COMMAND = 1
    const val MSG_SESSION_JOIN = 2
    const val MSG_SESSION_QUIT = 3
}

enum class ProtectFilterAction {
    BLOCK_BREAK,
    BLOCK_PLACE,
    INTERACT,
    CONTAINER_REMOVE,
    CONTAINER_ADD,
    ENTITY_KILL,
    ENTITY_SPAWN,
    CHAT,
    COMMAND,
    SESSION,
    SIGN,
}

data class ProtectCuboid(
    val world: String,
    val minX: Int,
    val minY: Int,
    val minZ: Int,
    val maxX: Int,
    val maxY: Int,
    val maxZ: Int,
)

data class ProtectQuery(
    val users: List<String> = emptyList(),
    val excludedUsers: List<String> = emptyList(),
    val sinceMillis: Long? = null,
    val untilMillis: Long? = null,
    val world: String? = null,
    val centerX: Int? = null,
    val centerY: Int? = null,
    val centerZ: Int? = null,
    val radius: Int? = null,
    val cuboid: ProtectCuboid? = null,
    val actions: Set<ProtectFilterAction> = emptySet(),
    val includeMaterials: Set<String> = emptySet(),
    val excludeMaterials: Set<String> = emptySet(),
    val textFilter: String? = null,
    val preview: Boolean = false,
    val countOnly: Boolean = false,
    val verbose: Boolean = false,
    val silent: Boolean = false,
    val useSelection: Boolean = false,
    val page: Int = 1,
)

data class ProtectBlockEntry(
    val id: Long = 0L,
    val time: Long,
    val actor: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val action: Int,
    val oldMaterial: String,
    val oldBlockData: String? = null,
    val oldExtra: String? = null,
    val newMaterial: String,
    val newBlockData: String? = null,
    val newExtra: String? = null,
    val rolledBack: Boolean = false,
)

data class ProtectContainerEntry(
    val id: Long = 0L,
    val time: Long,
    val actor: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val action: Int,
    val material: String,
    val amount: Int,
    val itemBase64: String,
    val rolledBack: Boolean = false,
)

data class ProtectEntityEntry(
    val id: Long = 0L,
    val time: Long,
    val actor: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val action: Int,
    val entityType: String,
    val entitySnapshot: String? = null,
    val rolledBack: Boolean = false,
)

data class ProtectMessageEntry(
    val id: Long = 0L,
    val time: Long,
    val actor: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val kind: Int,
    val message: String,
)

data class ProtectLookupLine(
    val time: Long,
    val actor: String,
    val verb: String,
    val subject: String,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val detail: String = "",
    val rolledBack: Boolean = false,
)

data class ProtectLookupPageSession(
    val lines: List<ProtectLookupLine>,
    val headerLabel: String,
)

data class ProtectUndoBatch(
    val wasRestore: Boolean,
    val blockIds: List<Long>,
    val containerIds: List<Long>,
    val entityIds: List<Long>,
    val revertRestoreItems: Boolean,
)

data class ProtectPreviewSession(
    val query: ProtectQuery,
    val restoreMode: Boolean,
    val previewBlocks: Map<Location, BlockData>,
)

data class ProtectPurgeResult(
    val deletedBlocks: Int,
    val deletedContainers: Int,
    val deletedEntities: Int,
    val deletedMessages: Int,
) {
    val total: Int get() = deletedBlocks + deletedContainers + deletedEntities + deletedMessages
}

data class ProtectTableCounts(
    val blocks: Long,
    val containers: Long,
    val entities: Long,
    val messages: Long,
)
