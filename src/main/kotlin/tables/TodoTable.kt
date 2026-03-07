package org.delcom.tables

import org.jetbrains.exposed.dao.id.UUIDTable
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp

object TodoTable : UUIDTable("todos") {
    val userId      = uuid("user_id")
    val title       = varchar("title", 100)
    val description = text("description")
    val cover       = text("cover").nullable()
    val isDone      = bool("is_done").default(false)
    /**
     * Urgency level stored as integer for proper ordering:
     *  1 = low, 2 = medium, 3 = high
     * Default 2 (medium)
     */
    val urgency     = integer("urgency").default(2)
    val createdAt   = timestamp("created_at")
    val updatedAt   = timestamp("updated_at")
}