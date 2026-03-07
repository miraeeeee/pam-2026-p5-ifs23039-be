package org.delcom.services

import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.util.cio.*
import io.ktor.utils.io.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.delcom.data.AppException
import org.delcom.data.DataResponse
import org.delcom.data.TodoRequest
import org.delcom.helpers.ServiceHelper
import org.delcom.helpers.UrgencyHelper
import org.delcom.helpers.ValidatorHelper
import org.delcom.repositories.ITodoRepository
import org.delcom.repositories.IUserRepository
import java.io.File
import java.util.UUID

class TodoService(
    private val userRepo: IUserRepository,
    private val todoRepo: ITodoRepository,
) {
    // ── GET /todos ────────────────────────────────────────────────────────────
    suspend fun getAll(call: ApplicationCall) {
        val user = ServiceHelper.getAuthUser(call, userRepo)

        val search      = call.request.queryParameters["search"] ?: ""
        val isDoneParam = call.request.queryParameters["is_done"]
        val isDone      = when (isDoneParam) {
            "1", "true"  -> true
            "0", "false" -> false
            else         -> null
        }

        // urgency filter: "low" | "medium" | "high" (null = all)
        val urgencyParam = call.request.queryParameters["urgency"]
        val urgency = if (!urgencyParam.isNullOrBlank() && UrgencyHelper.isValid(urgencyParam))
            urgencyParam.lowercase()
        else null

        val page    = call.request.queryParameters["page"]?.toIntOrNull()    ?: 1
        val perPage = call.request.queryParameters["perPage"]?.toIntOrNull() ?: 10

        val todos = todoRepo.getAll(user.id, search, isDone, urgency, page, perPage)

        call.respond(DataResponse("success", "Berhasil mengambil daftar todo", mapOf("todos" to todos)))
    }

    // ── GET /todos/stats ──────────────────────────────────────────────────────
    suspend fun getStats(call: ApplicationCall) {
        val user  = ServiceHelper.getAuthUser(call, userRepo)
        val stats = todoRepo.getStats(user.id)
        call.respond(DataResponse("success", "Berhasil mengambil statistik todo", stats))
    }

    // ── GET /todos/{id} ───────────────────────────────────────────────────────
    suspend fun getById(call: ApplicationCall) {
        val todoId = call.parameters["id"] ?: throw AppException(400, "Data todo tidak valid!")
        val user   = ServiceHelper.getAuthUser(call, userRepo)

        val todo = todoRepo.getById(todoId)
        if (todo == null || todo.userId != user.id) throw AppException(404, "Data todo tidak tersedia!")

        call.respond(DataResponse("success", "Berhasil mengambil data todo", mapOf("todo" to todo)))
    }

    // ── POST /todos ───────────────────────────────────────────────────────────
    suspend fun post(call: ApplicationCall) {
        val user    = ServiceHelper.getAuthUser(call, userRepo)
        val request = call.receive<TodoRequest>()
        request.userId = user.id

        val validator = ValidatorHelper(request.toMap())
        validator.required("title",       "Judul todo tidak boleh kosong")
        validator.required("description", "Deskripsi tidak boleh kosong")
        validator.validate()

        // Normalise & validate urgency
        request.urgency = normaliseUrgency(request.urgency)

        val todoId = todoRepo.create(request.toEntity())
        call.respond(DataResponse("success", "Berhasil menambahkan data todo", mapOf("todoId" to todoId)))
    }

    // ── PUT /todos/{id} ───────────────────────────────────────────────────────
    suspend fun put(call: ApplicationCall) {
        val todoId  = call.parameters["id"] ?: throw AppException(400, "Data todo tidak valid!")
        val user    = ServiceHelper.getAuthUser(call, userRepo)
        val request = call.receive<TodoRequest>()
        request.userId = user.id

        val validator = ValidatorHelper(request.toMap())
        validator.required("title",       "Judul todo tidak boleh kosong")
        validator.required("description", "Deskripsi tidak boleh kosong")
        validator.required("isDone",      "Status selesai tidak boleh kosong")
        validator.validate()

        request.urgency = normaliseUrgency(request.urgency)

        val oldTodo = todoRepo.getById(todoId)
        if (oldTodo == null || oldTodo.userId != user.id) throw AppException(404, "Data todo tidak tersedia!")
        request.cover = oldTodo.cover  // keep existing cover

        val ok = todoRepo.update(user.id, todoId, request.toEntity())
        if (!ok) throw AppException(400, "Gagal memperbarui data todo!")

        call.respond(DataResponse("success", "Berhasil mengubah data todo", null))
    }

    // ── PUT /todos/{id}/cover ─────────────────────────────────────────────────
    suspend fun putCover(call: ApplicationCall) {
        val todoId = call.parameters["id"] ?: throw AppException(400, "Data todo tidak valid!")
        val user   = ServiceHelper.getAuthUser(call, userRepo)

        val request = TodoRequest()
        request.userId = user.id

        val multipartData = call.receiveMultipart(formFieldLimit = 1024 * 1024 * 5)
        multipartData.forEachPart { part ->
            if (part is PartData.FileItem) {
                val ext      = part.originalFileName?.substringAfterLast('.', "")
                    ?.let { if (it.isNotEmpty()) ".$it" else "" } ?: ""
                val fileName = "${UUID.randomUUID()}$ext"
                val filePath = "uploads/todos/$fileName"
                withContext(Dispatchers.IO) {
                    val file = File(filePath)
                    file.parentFile?.mkdirs()
                    part.provider().copyAndClose(file.writeChannel())
                    request.cover = filePath
                }
            }
            part.dispose()
        }

        if (request.cover == null)          throw AppException(400, "Cover todo tidak tersedia!")
        if (!File(request.cover!!).exists()) throw AppException(400, "Cover todo gagal diunggah!")

        val oldTodo = todoRepo.getById(todoId)
        if (oldTodo == null || oldTodo.userId != user.id) throw AppException(404, "Data todo tidak tersedia!")

        // Copy other fields from existing todo
        request.title       = oldTodo.title
        request.description = oldTodo.description
        request.isDone      = oldTodo.isDone
        request.urgency     = oldTodo.urgency

        val ok = todoRepo.update(user.id, todoId, request.toEntity())
        if (!ok) throw AppException(400, "Gagal memperbarui cover todo!")

        // Delete old cover file
        oldTodo.cover?.let { path ->
            val old = File(path)
            if (old.exists()) old.delete()
        }

        call.respond(DataResponse("success", "Berhasil mengubah cover todo", null))
    }

    // ── DELETE /todos/{id} ────────────────────────────────────────────────────
    suspend fun delete(call: ApplicationCall) {
        val todoId = call.parameters["id"] ?: throw AppException(400, "Data todo tidak valid!")
        val user   = ServiceHelper.getAuthUser(call, userRepo)

        val oldTodo = todoRepo.getById(todoId)
        if (oldTodo == null || oldTodo.userId != user.id) throw AppException(404, "Data todo tidak tersedia!")

        val ok = todoRepo.delete(user.id, todoId)
        if (!ok) throw AppException(400, "Gagal menghapus data todo!")

        // Delete cover file if exists
        oldTodo.cover?.let { path ->
            val file = File(path)
            if (file.exists()) file.delete()
        }

        call.respond(DataResponse("success", "Berhasil menghapus data todo", null))
    }

    // ── GET /images/todos/{id} ────────────────────────────────────────────────
    suspend fun getCover(call: ApplicationCall) {
        val todoId = call.parameters["id"] ?: throw AppException(400, "Data todo tidak valid!")

        val todo = todoRepo.getById(todoId) ?: return call.respond(HttpStatusCode.NotFound)
        if (todo.cover == null) throw AppException(404, "Todo belum memiliki cover")

        val file = File(todo.cover!!)
        if (!file.exists()) throw AppException(404, "Cover todo tidak tersedia")

        call.respondFile(file)
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun normaliseUrgency(raw: String): String {
        val lower = raw.lowercase()
        return if (UrgencyHelper.isValid(lower)) lower else "medium"
    }
}