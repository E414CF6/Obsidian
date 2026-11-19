package obsidian.modules.protect

import obsidian.core.database.DatabaseDialect
import obsidian.core.database.DatabaseManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types

/**
 * JDBC persistence repository for audit tables (`protect_blocks`,
 * `protect_containers`, `protect_entities`, `protect_messages`).
 */
class ProtectRepository(private val db: DatabaseManager) {

    private val blocksTable = db.table("protect_blocks")
    private val containersTable = db.table("protect_containers")
    private val entitiesTable = db.table("protect_entities")
    private val messagesTable = db.table("protect_messages")

    suspend fun insertBatch(
        blocks: List<ProtectBlockEntry>,
        containers: List<ProtectContainerEntry>,
        entities: List<ProtectEntityEntry>,
        messages: List<ProtectMessageEntry>,
    ) {
        if (blocks.isEmpty() && containers.isEmpty() && entities.isEmpty() && messages.isEmpty()) return
        db.query { conn ->
            val oldAuto = conn.autoCommit
            conn.autoCommit = false
            try {
                if (blocks.isNotEmpty()) {
                    conn.prepareStatement(
                        """
                        INSERT INTO `$blocksTable`
                        (`time`, `actor`, `world`, `x`, `y`, `z`, `action`, `old_material`, `old_blockdata`, `old_extra`, `new_material`, `new_blockdata`, `new_extra`, `rolled_back`)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                        """.trimIndent()
                    ).use { ps ->
                        for (b in blocks) {
                            ps.setLong(1, b.time)
                            ps.setString(2, b.actor.take(64))
                            ps.setString(3, b.world.take(128))
                            ps.setInt(4, b.x)
                            ps.setInt(5, b.y)
                            ps.setInt(6, b.z)
                            ps.setInt(7, b.action)
                            ps.setString(8, b.oldMaterial.take(64))
                            setNullableString(ps, 9, b.oldBlockData)
                            setNullableString(ps, 10, b.oldExtra)
                            ps.setString(11, b.newMaterial.take(64))
                            setNullableString(ps, 12, b.newBlockData)
                            setNullableString(ps, 13, b.newExtra)
                            ps.setBoolean(14, b.rolledBack)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                if (containers.isNotEmpty()) {
                    conn.prepareStatement(
                        """
                        INSERT INTO `$containersTable`
                        (`time`, `actor`, `world`, `x`, `y`, `z`, `action`, `material`, `amount`, `item_base64`, `rolled_back`)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                        """.trimIndent()
                    ).use { ps ->
                        for (c in containers) {
                            ps.setLong(1, c.time)
                            ps.setString(2, c.actor.take(64))
                            ps.setString(3, c.world.take(128))
                            ps.setInt(4, c.x)
                            ps.setInt(5, c.y)
                            ps.setInt(6, c.z)
                            ps.setInt(7, c.action)
                            ps.setString(8, c.material.take(64))
                            ps.setInt(9, c.amount)
                            ps.setString(10, c.itemBase64)
                            ps.setBoolean(11, c.rolledBack)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                if (entities.isNotEmpty()) {
                    conn.prepareStatement(
                        """
                        INSERT INTO `$entitiesTable`
                        (`time`, `actor`, `world`, `x`, `y`, `z`, `action`, `entity_type`, `entity_snapshot`, `rolled_back`)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
                        """.trimIndent()
                    ).use { ps ->
                        for (e in entities) {
                            ps.setLong(1, e.time)
                            ps.setString(2, e.actor.take(64))
                            ps.setString(3, e.world.take(128))
                            ps.setInt(4, e.x)
                            ps.setInt(5, e.y)
                            ps.setInt(6, e.z)
                            ps.setInt(7, e.action)
                            ps.setString(8, e.entityType.take(64))
                            setNullableString(ps, 9, e.entitySnapshot)
                            ps.setBoolean(10, e.rolledBack)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                if (messages.isNotEmpty()) {
                    conn.prepareStatement(
                        """
                        INSERT INTO `$messagesTable`
                        (`time`, `actor`, `world`, `x`, `y`, `z`, `kind`, `message`)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?);
                        """.trimIndent()
                    ).use { ps ->
                        for (m in messages) {
                            ps.setLong(1, m.time)
                            ps.setString(2, m.actor.take(64))
                            ps.setString(3, m.world.take(128))
                            ps.setInt(4, m.x)
                            ps.setInt(5, m.y)
                            ps.setInt(6, m.z)
                            ps.setInt(7, m.kind)
                            ps.setString(8, m.message)
                            ps.addBatch()
                        }
                        ps.executeBatch()
                    }
                }
                conn.commit()
            } catch (ex: Exception) {
                runCatching { conn.rollback() }
                throw ex
            } finally {
                runCatching { conn.autoCommit = oldAuto }
            }
        }
    }

    suspend fun inspectBlock(world: String, x: Int, y: Int, z: Int, limit: Int = 50): List<ProtectBlockEntry> =
        db.query { conn ->
            conn.prepareStatement(
                "SELECT * FROM `$blocksTable` WHERE `world` = ? AND `x` = ? AND `y` = ? AND `z` = ? ORDER BY `time` DESC, `id` DESC LIMIT ?;"
            ).use { ps ->
                ps.setString(1, world)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.setInt(5, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(mapBlock(rs))
                    }
                }
            }
        }

    suspend fun inspectContainer(world: String, x: Int, y: Int, z: Int, limit: Int = 50): List<ProtectContainerEntry> =
        db.query { conn ->
            conn.prepareStatement(
                "SELECT * FROM `$containersTable` WHERE `world` = ? AND `x` = ? AND `y` = ? AND `z` = ? ORDER BY `time` DESC, `id` DESC LIMIT ?;"
            ).use { ps ->
                ps.setString(1, world)
                ps.setInt(2, x)
                ps.setInt(3, y)
                ps.setInt(4, z)
                ps.setInt(5, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(mapContainer(rs))
                    }
                }
            }
        }

    suspend fun queryBlocks(
        query: ProtectQuery,
        rolledBackFilter: Boolean? = null,
        ascending: Boolean = false,
        limit: Int = 5000,
    ): List<ProtectBlockEntry> {
        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any>()
        appendCommonSpatialAndUserClauses(query, clauses, params)

        if (rolledBackFilter != null) {
            clauses += "`rolled_back` = ?"
            params += rolledBackFilter
        }

        val actionCodes = mutableListOf<Int>()
        if (ProtectFilterAction.BLOCK_BREAK in query.actions) actionCodes += ProtectActions.BLOCK_BREAK
        if (ProtectFilterAction.BLOCK_PLACE in query.actions) actionCodes += ProtectActions.BLOCK_PLACE
        if (ProtectFilterAction.INTERACT in query.actions) actionCodes += ProtectActions.BLOCK_INTERACT
        if (ProtectFilterAction.SIGN in query.actions) {
            clauses += "(`old_material` LIKE '%SIGN%' OR `new_material` LIKE '%SIGN%')"
        }
        if (actionCodes.isNotEmpty()) {
            clauses += "`action` IN (${actionCodes.joinToString(",") { "?" }})"
            params.addAll(actionCodes)
        } else if (query.actions.isNotEmpty() && ProtectFilterAction.SIGN !in query.actions) {
            return emptyList()
        }

        if (query.includeMaterials.isNotEmpty()) {
            val ph = query.includeMaterials.joinToString(",") { "?" }
            clauses += "(`old_material` IN ($ph) OR `new_material` IN ($ph))"
            params.addAll(query.includeMaterials)
            params.addAll(query.includeMaterials)
        }
        if (query.excludeMaterials.isNotEmpty()) {
            val ph = query.excludeMaterials.joinToString(",") { "?" }
            clauses += "(`old_material` NOT IN ($ph) AND `new_material` NOT IN ($ph))"
            params.addAll(query.excludeMaterials)
            params.addAll(query.excludeMaterials)
        }
        if (!query.textFilter.isNullOrBlank()) {
            clauses += "(LOWER(COALESCE(`old_extra`, '')) LIKE LOWER(?) OR LOWER(COALESCE(`new_extra`, '')) LIKE LOWER(?))"
            val pattern = "%${query.textFilter}%"
            params += pattern
            params += pattern
        }

        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val order = if (ascending) "ORDER BY `time` ASC, `id` ASC" else "ORDER BY `time` DESC, `id` DESC"
        val sql = "SELECT * FROM `$blocksTable` $where $order LIMIT ?;"

        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                bindParams(ps, params, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(mapBlock(rs))
                    }
                }
            }
        }
    }

    suspend fun queryContainers(
        query: ProtectQuery,
        rolledBackFilter: Boolean? = null,
        ascending: Boolean = false,
        limit: Int = 5000,
    ): List<ProtectContainerEntry> {
        val actionCodes = mutableListOf<Int>()
        if (ProtectFilterAction.CONTAINER_REMOVE in query.actions) actionCodes += ProtectActions.CONTAINER_REMOVE
        if (ProtectFilterAction.CONTAINER_ADD in query.actions) actionCodes += ProtectActions.CONTAINER_ADD
        if (query.actions.isNotEmpty() && actionCodes.isEmpty()) return emptyList()

        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any>()
        appendCommonSpatialAndUserClauses(query, clauses, params)

        if (rolledBackFilter != null) {
            clauses += "`rolled_back` = ?"
            params += rolledBackFilter
        }
        if (actionCodes.isNotEmpty()) {
            clauses += "`action` IN (${actionCodes.joinToString(",") { "?" }})"
            params.addAll(actionCodes)
        }
        if (query.includeMaterials.isNotEmpty()) {
            val ph = query.includeMaterials.joinToString(",") { "?" }
            clauses += "`material` IN ($ph)"
            params.addAll(query.includeMaterials)
        }
        if (query.excludeMaterials.isNotEmpty()) {
            val ph = query.excludeMaterials.joinToString(",") { "?" }
            clauses += "`material` NOT IN ($ph)"
            params.addAll(query.excludeMaterials)
        }

        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val order = if (ascending) "ORDER BY `time` ASC, `id` ASC" else "ORDER BY `time` DESC, `id` DESC"
        val sql = "SELECT * FROM `$containersTable` $where $order LIMIT ?;"

        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                bindParams(ps, params, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(mapContainer(rs))
                    }
                }
            }
        }
    }

    suspend fun queryEntities(
        query: ProtectQuery,
        rolledBackFilter: Boolean? = null,
        ascending: Boolean = false,
        limit: Int = 2000,
    ): List<ProtectEntityEntry> {
        val actionCodes = mutableListOf<Int>()
        if (ProtectFilterAction.ENTITY_KILL in query.actions) actionCodes += ProtectActions.ENTITY_KILL
        if (ProtectFilterAction.ENTITY_SPAWN in query.actions) actionCodes += ProtectActions.ENTITY_SPAWN
        if (query.actions.isNotEmpty() && actionCodes.isEmpty()) return emptyList()

        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any>()
        appendCommonSpatialAndUserClauses(query, clauses, params)

        if (rolledBackFilter != null) {
            clauses += "`rolled_back` = ?"
            params += rolledBackFilter
        }
        if (actionCodes.isNotEmpty()) {
            clauses += "`action` IN (${actionCodes.joinToString(",") { "?" }})"
            params.addAll(actionCodes)
        }
        if (query.includeMaterials.isNotEmpty()) {
            val ph = query.includeMaterials.joinToString(",") { "?" }
            clauses += "`entity_type` IN ($ph)"
            params.addAll(query.includeMaterials)
        }
        if (query.excludeMaterials.isNotEmpty()) {
            val ph = query.excludeMaterials.joinToString(",") { "?" }
            clauses += "`entity_type` NOT IN ($ph)"
            params.addAll(query.excludeMaterials)
        }

        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val order = if (ascending) "ORDER BY `time` ASC, `id` ASC" else "ORDER BY `time` DESC, `id` DESC"
        val sql = "SELECT * FROM `$entitiesTable` $where $order LIMIT ?;"

        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                bindParams(ps, params, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(mapEntity(rs))
                    }
                }
            }
        }
    }

    suspend fun queryBlocksByIds(ids: List<Long>, ascending: Boolean): List<ProtectBlockEntry> =
        queryByIds(blocksTable, ids, ascending, ::mapBlock)

    suspend fun queryContainersByIds(ids: List<Long>, ascending: Boolean): List<ProtectContainerEntry> =
        queryByIds(containersTable, ids, ascending, ::mapContainer)

    suspend fun queryEntitiesByIds(ids: List<Long>, ascending: Boolean): List<ProtectEntityEntry> =
        queryByIds(entitiesTable, ids, ascending, ::mapEntity)

    private suspend fun <T> queryByIds(
        table: String,
        ids: List<Long>,
        ascending: Boolean,
        mapper: (ResultSet) -> T,
    ): List<T> {
        if (ids.isEmpty()) return emptyList()
        val order = if (ascending) "ORDER BY `time` ASC, `id` ASC" else "ORDER BY `time` DESC, `id` DESC"
        return db.query { conn ->
            val out = mutableListOf<T>()
            for (chunk in ids.chunked(500)) {
                val ph = chunk.joinToString(",") { "?" }
                conn.prepareStatement("SELECT * FROM `$table` WHERE `id` IN ($ph) $order;").use { ps ->
                    chunk.forEachIndexed { idx, id -> ps.setLong(idx + 1, id) }
                    ps.executeQuery().use { rs ->
                        while (rs.next()) out += mapper(rs)
                    }
                }
            }
            out
        }
    }

    suspend fun queryMessages(query: ProtectQuery, limit: Int = 1000): List<ProtectMessageEntry> {
        val kindCodes = mutableListOf<Int>()
        if (ProtectFilterAction.CHAT in query.actions) kindCodes += ProtectActions.MSG_CHAT
        if (ProtectFilterAction.COMMAND in query.actions) kindCodes += ProtectActions.MSG_COMMAND
        if (ProtectFilterAction.SESSION in query.actions) {
            kindCodes += ProtectActions.MSG_SESSION_JOIN
            kindCodes += ProtectActions.MSG_SESSION_QUIT
        }
        if (query.actions.isNotEmpty() && kindCodes.isEmpty()) return emptyList()

        val clauses = mutableListOf<String>()
        val params = mutableListOf<Any>()
        appendCommonSpatialAndUserClauses(query, clauses, params)
        if (kindCodes.isNotEmpty()) {
            clauses += "`kind` IN (${kindCodes.joinToString(",") { "?" }})"
            params.addAll(kindCodes)
        }
        if (!query.textFilter.isNullOrBlank()) {
            clauses += "LOWER(`message`) LIKE LOWER(?)"
            params += "%${query.textFilter}%"
        }

        val where = if (clauses.isEmpty()) "" else "WHERE ${clauses.joinToString(" AND ")}"
        val sql = "SELECT * FROM `$messagesTable` $where ORDER BY `time` DESC, `id` DESC LIMIT ?;"

        return db.query { conn ->
            conn.prepareStatement(sql).use { ps ->
                bindParams(ps, params, limit)
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) add(mapMessage(rs))
                    }
                }
            }
        }
    }

    suspend fun setBlocksRolledBack(ids: List<Long>, rolledBack: Boolean) {
        updateRolledBackFlag(blocksTable, ids, rolledBack)
    }

    suspend fun setContainersRolledBack(ids: List<Long>, rolledBack: Boolean) {
        updateRolledBackFlag(containersTable, ids, rolledBack)
    }

    suspend fun setEntitiesRolledBack(ids: List<Long>, rolledBack: Boolean) {
        updateRolledBackFlag(entitiesTable, ids, rolledBack)
    }

    suspend fun purgeBefore(
        cutoffMillis: Long,
        world: String? = null,
        centerX: Int? = null,
        centerY: Int? = null,
        centerZ: Int? = null,
        radius: Int? = null,
        vacuumSqlite: Boolean = false,
    ): ProtectPurgeResult {
        val clauses = mutableListOf("`time` < ?")
        val params = mutableListOf<Any>(cutoffMillis)
        if (!world.isNullOrBlank()) {
            clauses += "`world` = ?"
            params += world
        }
        if (radius != null && centerX != null && centerY != null && centerZ != null) {
            clauses += "`x` BETWEEN ? AND ?"
            clauses += "`y` BETWEEN ? AND ?"
            clauses += "`z` BETWEEN ? AND ?"
            params += (centerX - radius)
            params += (centerX + radius)
            params += (centerY - radius)
            params += (centerY + radius)
            params += (centerZ - radius)
            params += (centerZ + radius)
        }
        val where = clauses.joinToString(" AND ")

        val result = db.query { conn ->
            val oldAuto = conn.autoCommit
            conn.autoCommit = false
            try {
                fun deleteFrom(table: String): Int {
                    return conn.prepareStatement("DELETE FROM `$table` WHERE $where;").use { ps ->
                        for ((idx, v) in params.withIndex()) {
                            ps.setObject(idx + 1, v)
                        }
                        ps.executeUpdate()
                    }
                }

                val b = deleteFrom(blocksTable)
                val c = deleteFrom(containersTable)
                val e = deleteFrom(entitiesTable)
                val m = deleteFrom(messagesTable)
                conn.commit()
                ProtectPurgeResult(b, c, e, m)
            } catch (ex: Exception) {
                runCatching { conn.rollback() }
                throw ex
            } finally {
                runCatching { conn.autoCommit = oldAuto }
            }
        }

        if (vacuumSqlite && db.dialect == DatabaseDialect.SQLITE) {
            db.query { conn ->
                conn.createStatement().use { st ->
                    st.executeUpdate("VACUUM;")
                }
            }
        }
        return result
    }

    suspend fun tableCounts(): ProtectTableCounts = db.query { conn ->
        fun countOf(table: String): Long = conn.createStatement().use { st ->
            st.executeQuery("SELECT COUNT(*) FROM `$table`;").use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
        ProtectTableCounts(
            blocks = countOf(blocksTable),
            containers = countOf(containersTable),
            entities = countOf(entitiesTable),
            messages = countOf(messagesTable),
        )
    }

    private suspend fun updateRolledBackFlag(table: String, ids: List<Long>, rolledBack: Boolean) {
        if (ids.isEmpty()) return
        db.query { conn ->
            val oldAuto = conn.autoCommit
            conn.autoCommit = false
            try {
                for (chunk in ids.chunked(500)) {
                    val placeholders = chunk.joinToString(",") { "?" }
                    conn.prepareStatement("UPDATE `$table` SET `rolled_back` = ? WHERE `id` IN ($placeholders);")
                        .use { ps ->
                            ps.setBoolean(1, rolledBack)
                            chunk.forEachIndexed { idx, id -> ps.setLong(idx + 2, id) }
                            ps.executeUpdate()
                        }
                }
                conn.commit()
            } catch (ex: Exception) {
                runCatching { conn.rollback() }
                throw ex
            } finally {
                runCatching { conn.autoCommit = oldAuto }
            }
        }
    }

    private fun appendCommonSpatialAndUserClauses(
        query: ProtectQuery,
        clauses: MutableList<String>,
        params: MutableList<Any>,
    ) {
        query.sinceMillis?.let {
            clauses += "`time` >= ?"
            params += it
        }
        query.untilMillis?.let {
            clauses += "`time` <= ?"
            params += it
        }
        if (query.users.isNotEmpty()) {
            val ph = query.users.joinToString(",") { "LOWER(?)" }
            clauses += "LOWER(`actor`) IN ($ph)"
            params.addAll(query.users)
        }
        if (query.excludedUsers.isNotEmpty()) {
            val ph = query.excludedUsers.joinToString(",") { "LOWER(?)" }
            clauses += "LOWER(`actor`) NOT IN ($ph)"
            params.addAll(query.excludedUsers)
        }
        val cuboid = query.cuboid
        if (cuboid != null) {
            clauses += "`world` = ?"
            clauses += "`x` BETWEEN ? AND ?"
            clauses += "`y` BETWEEN ? AND ?"
            clauses += "`z` BETWEEN ? AND ?"
            params += cuboid.world
            params += cuboid.minX
            params += cuboid.maxX
            params += cuboid.minY
            params += cuboid.maxY
            params += cuboid.minZ
            params += cuboid.maxZ
        } else {
            if (!query.world.isNullOrBlank()) {
                clauses += "`world` = ?"
                params += query.world
            }
            val r = query.radius
            val cx = query.centerX
            val cy = query.centerY
            val cz = query.centerZ
            if (r != null && cx != null && cy != null && cz != null) {
                clauses += "`x` BETWEEN ? AND ?"
                clauses += "`y` BETWEEN ? AND ?"
                clauses += "`z` BETWEEN ? AND ?"
                params += (cx - r)
                params += (cx + r)
                params += (cy - r)
                params += (cy + r)
                params += (cz - r)
                params += (cz + r)
            }
        }
    }

    private fun bindParams(ps: PreparedStatement, params: List<Any>, limit: Int) {
        var idx = 1
        for (p in params) {
            when (p) {
                is Boolean -> ps.setBoolean(idx++, p)
                is Int -> ps.setInt(idx++, p)
                is Long -> ps.setLong(idx++, p)
                else -> ps.setString(idx++, p.toString())
            }
        }
        ps.setInt(idx, limit)
    }

    private fun setNullableString(ps: PreparedStatement, idx: Int, value: String?) {
        if (value == null) ps.setNull(idx, Types.VARCHAR) else ps.setString(idx, value)
    }

    private fun mapBlock(rs: ResultSet): ProtectBlockEntry = ProtectBlockEntry(
        id = rs.getLong("id"),
        time = rs.getLong("time"),
        actor = rs.getString("actor"),
        world = rs.getString("world"),
        x = rs.getInt("x"),
        y = rs.getInt("y"),
        z = rs.getInt("z"),
        action = rs.getInt("action"),
        oldMaterial = rs.getString("old_material"),
        oldBlockData = rs.getString("old_blockdata"),
        oldExtra = rs.getString("old_extra"),
        newMaterial = rs.getString("new_material"),
        newBlockData = rs.getString("new_blockdata"),
        newExtra = rs.getString("new_extra"),
        rolledBack = rs.getBoolean("rolled_back"),
    )

    private fun mapContainer(rs: ResultSet): ProtectContainerEntry = ProtectContainerEntry(
        id = rs.getLong("id"),
        time = rs.getLong("time"),
        actor = rs.getString("actor"),
        world = rs.getString("world"),
        x = rs.getInt("x"),
        y = rs.getInt("y"),
        z = rs.getInt("z"),
        action = rs.getInt("action"),
        material = rs.getString("material"),
        amount = rs.getInt("amount"),
        itemBase64 = rs.getString("item_base64"),
        rolledBack = rs.getBoolean("rolled_back"),
    )

    private fun mapEntity(rs: ResultSet): ProtectEntityEntry = ProtectEntityEntry(
        id = rs.getLong("id"),
        time = rs.getLong("time"),
        actor = rs.getString("actor"),
        world = rs.getString("world"),
        x = rs.getInt("x"),
        y = rs.getInt("y"),
        z = rs.getInt("z"),
        action = rs.getInt("action"),
        entityType = rs.getString("entity_type"),
        entitySnapshot = rs.getString("entity_snapshot"),
        rolledBack = rs.getBoolean("rolled_back"),
    )

    private fun mapMessage(rs: ResultSet): ProtectMessageEntry = ProtectMessageEntry(
        id = rs.getLong("id"),
        time = rs.getLong("time"),
        actor = rs.getString("actor"),
        world = rs.getString("world"),
        x = rs.getInt("x"),
        y = rs.getInt("y"),
        z = rs.getInt("z"),
        kind = rs.getInt("kind"),
        message = rs.getString("message"),
    )
}
