package org.delcom.repositories

import org.delcom.dao.TodoDAO
import org.delcom.entities.Todo
import org.delcom.helpers.UrgencyHelper
import org.delcom.helpers.suspendTransaction
import org.delcom.helpers.todoDAOToModel
import org.delcom.tables.TodoTable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import java.util.UUID

class TodoRepository : ITodoRepository {

    override suspend fun getAll(
        userId  : String,
        search  : String,
        isDone  : Boolean?,
        urgency : String?,
        page    : Int,
        perPage : Int,
    ): List<Todo> = suspendTransaction {
        val userUuid = UUID.fromString(userId)
        val offset   = ((page - 1) * perPage).toLong()

        TodoDAO.find {
            // Always filter by owner
            var op: Op<Boolean> = TodoTable.userId eq userUuid

            // Optional title search (case-insensitive)
            if (search.isNotBlank()) {
                op = op and (TodoTable.title.lowerCase() like "%${search.lowercase()}%")
            }

            // Optional isDone filter
            if (isDone != null) {
                op = op and (TodoTable.isDone eq isDone)
            }

            // Optional urgency filter – convert string to int for DB query
            if (!urgency.isNullOrBlank()) {
                val urgencyInt = UrgencyHelper.toInt(urgency)
                op = op and (TodoTable.urgency eq urgencyInt)
            }

            op
        }
            // Sort: urgency DESC (high=3 first), then newest first
            .orderBy(
                TodoTable.urgency   to SortOrder.DESC,
                TodoTable.createdAt to SortOrder.DESC,
            )
            .limit(perPage)
            .offset(offset)
            .map(::todoDAOToModel)
    }

    override suspend fun getById(todoId: String): Todo? = suspendTransaction {
        TodoDAO
            .find { TodoTable.id eq UUID.fromString(todoId) }
            .limit(1)
            .map(::todoDAOToModel)
            .firstOrNull()
    }

    override suspend fun create(todo: Todo): String = suspendTransaction {
        val dao = TodoDAO.new {
            userId      = UUID.fromString(todo.userId)
            title       = todo.title
            description = todo.description
            cover       = todo.cover
            isDone      = todo.isDone
            urgency     = UrgencyHelper.toInt(todo.urgency)   // String → Int
            createdAt   = todo.createdAt
            updatedAt   = todo.updatedAt
        }
        dao.id.value.toString()
    }

    override suspend fun update(userId: String, todoId: String, newTodo: Todo): Boolean = suspendTransaction {
        val dao = TodoDAO
            .find {
                (TodoTable.id     eq UUID.fromString(todoId)) and
                        (TodoTable.userId eq UUID.fromString(userId))
            }
            .limit(1)
            .firstOrNull()

        if (dao != null) {
            dao.title       = newTodo.title
            dao.description = newTodo.description
            dao.cover       = newTodo.cover
            dao.isDone      = newTodo.isDone
            dao.urgency     = UrgencyHelper.toInt(newTodo.urgency)   // String → Int
            dao.updatedAt   = newTodo.updatedAt
            true
        } else false
    }

    override suspend fun delete(userId: String, todoId: String): Boolean = suspendTransaction {
        val rows = TodoTable.deleteWhere {
            (TodoTable.id     eq UUID.fromString(todoId)) and
                    (TodoTable.userId eq UUID.fromString(userId))
        }
        rows >= 1
    }

    override suspend fun getStats(userId: String): Map<String, Long> = suspendTransaction {
        val uuid = UUID.fromString(userId)

        val total      = TodoDAO.find { TodoTable.userId eq uuid }.count()
        val finished   = TodoDAO.find { (TodoTable.userId eq uuid) and (TodoTable.isDone eq true)  }.count()
        val unfinished = TodoDAO.find { (TodoTable.userId eq uuid) and (TodoTable.isDone eq false) }.count()
        val low        = TodoDAO.find { (TodoTable.userId eq uuid) and (TodoTable.urgency eq 1) }.count()
        val medium     = TodoDAO.find { (TodoTable.userId eq uuid) and (TodoTable.urgency eq 2) }.count()
        val high       = TodoDAO.find { (TodoTable.userId eq uuid) and (TodoTable.urgency eq 3) }.count()

        mapOf(
            "total"      to total,
            "finished"   to finished,
            "unfinished" to unfinished,
            "low"        to low,
            "medium"     to medium,
            "high"       to high,
        )
    }
}