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
import kotlinx.datetime.Clock
import org.delcom.data.AppException
import org.delcom.data.AuthRequest
import org.delcom.data.DataResponse
import org.delcom.data.UserResponse
import org.delcom.helpers.ServiceHelper
import org.delcom.helpers.ValidatorHelper
import org.delcom.helpers.hashPassword
import org.delcom.helpers.verifyPassword
import org.delcom.repositories.IRefreshTokenRepository
import org.delcom.repositories.IUserRepository
import java.io.File
import java.util.UUID

class UserService(
    private val userRepo         : IUserRepository,
    private val refreshTokenRepo : IRefreshTokenRepository,
) {
    // ── GET /users/me ─────────────────────────────────────────────────────────
    suspend fun getMe(call: ApplicationCall) {
        val user = ServiceHelper.getAuthUser(call, userRepo)
        call.respond(
            DataResponse(
                "success",
                "Berhasil mengambil informasi akun saya",
                mapOf(
                    "user" to UserResponse(
                        id        = user.id,
                        name      = user.name,
                        username  = user.username,
                        photo     = user.photo,
                        bio       = user.bio,
                        createdAt = user.createdAt,
                        updatedAt = user.updatedAt,
                    )
                )
            )
        )
    }

    // ── PUT /users/me ─────────────────────────────────────────────────────────
    // Updates: name, username, bio (Tentang)
    suspend fun putMe(call: ApplicationCall) {
        val user    = ServiceHelper.getAuthUser(call, userRepo)
        val request = call.receive<AuthRequest>()

        val validator = ValidatorHelper(request.toMap())
        validator.required("name",     "Nama tidak boleh kosong")
        validator.required("username", "Username tidak boleh kosong")
        validator.validate()

        // Check username uniqueness (allow keeping same username)
        val existUser = userRepo.getByUsername(request.username)
        if (existUser != null && existUser.id != user.id) {
            throw AppException(409, "Akun dengan username ini sudah terdaftar!")
        }

        user.name      = request.name
        user.username  = request.username
        // bio can be empty string to clear it, or null to keep it unchanged —
        // here we always update to what the client sends (including null).
        user.bio       = request.bio
        user.updatedAt = Clock.System.now()

        val ok = userRepo.update(user.id, user)
        if (!ok) throw AppException(400, "Gagal memperbarui data profile!")

        call.respond(DataResponse("success", "Berhasil mengubah data profile", null))
    }

    // ── PUT /users/me/photo ───────────────────────────────────────────────────
    suspend fun putMyPhoto(call: ApplicationCall) {
        val user     = ServiceHelper.getAuthUser(call, userRepo)
        var newPhoto : String? = null

        val multipartData = call.receiveMultipart(formFieldLimit = 1024 * 1024 * 5)
        multipartData.forEachPart { part ->
            if (part is PartData.FileItem) {
                val ext      = part.originalFileName?.substringAfterLast('.', "")
                    ?.let { if (it.isNotEmpty()) ".$it" else "" } ?: ""
                val fileName = "${UUID.randomUUID()}$ext"
                val filePath = "uploads/users/$fileName"
                withContext(Dispatchers.IO) {
                    val file = File(filePath)
                    file.parentFile?.mkdirs()
                    part.provider().copyAndClose(file.writeChannel())
                    newPhoto = filePath
                }
            }
            part.dispose()
        }

        if (newPhoto == null)           throw AppException(400, "Photo profile tidak tersedia!")
        if (!File(newPhoto!!).exists()) throw AppException(400, "Photo profile gagal diunggah!")

        val oldPhoto   = user.photo
        user.photo     = newPhoto
        user.updatedAt = Clock.System.now()

        val ok = userRepo.update(user.id, user)
        if (!ok) throw AppException(400, "Gagal memperbarui photo profile!")

        // Delete old photo
        oldPhoto?.let { path ->
            val old = File(path)
            if (old.exists()) old.delete()
        }

        call.respond(DataResponse("success", "Berhasil mengubah photo profile", null))
    }

    // ── PUT /users/me/password ────────────────────────────────────────────────
    suspend fun putMyPassword(call: ApplicationCall) {
        val user    = ServiceHelper.getAuthUser(call, userRepo)
        val request = call.receive<AuthRequest>()

        val validator = ValidatorHelper(request.toMap())
        validator.required("password",    "Kata sandi lama tidak boleh kosong")
        validator.required("newPassword", "Kata sandi baru tidak boleh kosong")
        validator.validate()

        if (!verifyPassword(request.password, user.password)) {
            throw AppException(400, "Kata sandi lama tidak valid!")
        }

        user.password  = hashPassword(request.newPassword)
        user.updatedAt = Clock.System.now()

        val ok = userRepo.update(user.id, user)
        if (!ok) throw AppException(400, "Gagal mengubah kata sandi!")

        // Invalidate all sessions after password change
        refreshTokenRepo.deleteByUserId(user.id)

        call.respond(DataResponse("success", "Berhasil mengubah kata sandi", null))
    }

    // ── GET /images/users/{id} ────────────────────────────────────────────────
    suspend fun getPhoto(call: ApplicationCall) {
        val userId = call.parameters["id"] ?: throw AppException(400, "User id tidak valid!")

        val user = userRepo.getById(userId) ?: throw AppException(404, "User tidak ditemukan!")
        if (user.photo == null) throw AppException(404, "User belum memiliki photo profile")

        val file = File(user.photo!!)
        if (!file.exists()) throw AppException(404, "Photo profile tidak tersedia")

        call.respondFile(file)
    }
}