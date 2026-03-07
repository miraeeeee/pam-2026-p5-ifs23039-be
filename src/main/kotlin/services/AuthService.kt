package org.delcom.services

import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import org.delcom.data.AppException
import org.delcom.data.AuthRequest
import org.delcom.data.DataResponse
import org.delcom.data.RefreshTokenRequest
import org.delcom.entities.RefreshToken
import org.delcom.helpers.JWTConstants
import org.delcom.helpers.ValidatorHelper
import org.delcom.helpers.hashPassword
import org.delcom.helpers.verifyPassword
import org.delcom.repositories.IRefreshTokenRepository
import org.delcom.repositories.IUserRepository
import java.util.*

class AuthService(
    private val jwtSecret              : String,
    private val userRepository         : IUserRepository,
    private val refreshTokenRepository : IRefreshTokenRepository,
) {
    // ── POST /auth/register ───────────────────────────────────────────────────
    suspend fun postRegister(call: ApplicationCall) {
        val request = call.receive<AuthRequest>()

        val validator = ValidatorHelper(request.toMap())
        validator.required("name",     "Nama tidak boleh kosong")
        validator.required("username", "Username tidak boleh kosong")
        validator.required("password", "Password tidak boleh kosong")
        validator.validate()

        if (userRepository.getByUsername(request.username) != null) {
            throw AppException(409, "Akun dengan username ini sudah terdaftar!")
        }

        request.password = hashPassword(request.password)
        val userId = userRepository.create(request.toEntity())

        call.respond(DataResponse("success", "Berhasil melakukan pendaftaran", mapOf("userId" to userId)))
    }

    // ── POST /auth/login ──────────────────────────────────────────────────────
    suspend fun postLogin(call: ApplicationCall) {
        val request = call.receive<AuthRequest>()

        val validator = ValidatorHelper(request.toMap())
        validator.required("username", "Username tidak boleh kosong")
        validator.required("password", "Password tidak boleh kosong")
        validator.validate()

        val existUser = userRepository.getByUsername(request.username)
            ?: throw AppException(404, "Kredensial yang digunakan tidak valid!")

        if (!verifyPassword(request.password, existUser.password)) {
            throw AppException(404, "Kredensial yang digunakan tidak valid!")
        }

        val authToken = buildJwt(existUser.id)

        // Invalidate old tokens then issue new pair
        refreshTokenRepository.deleteByUserId(existUser.id)
        val strRefreshToken = UUID.randomUUID().toString()
        refreshTokenRepository.create(
            RefreshToken(userId = existUser.id, authToken = authToken, refreshToken = strRefreshToken)
        )

        call.respond(
            DataResponse(
                "success",
                "Berhasil melakukan login",
                mapOf("authToken" to authToken, "refreshToken" to strRefreshToken),
            )
        )
    }

    // ── POST /auth/refresh-token ──────────────────────────────────────────────
    suspend fun postRefreshToken(call: ApplicationCall) {
        val request = call.receive<RefreshTokenRequest>()

        val validator = ValidatorHelper(request.toMap())
        validator.required("refreshToken", "Refresh Token tidak boleh kosong")
        validator.required("authToken",    "Auth Token tidak boleh kosong")
        validator.validate()

        val existRefreshToken = refreshTokenRepository.getByToken(
            refreshToken = request.refreshToken,
            authToken    = request.authToken,
        )

        // Always delete the old token (rotation – even on failure)
        refreshTokenRepository.delete(request.authToken)

        if (existRefreshToken == null) throw AppException(401, "Token tidak valid!")

        val user = userRepository.getById(existRefreshToken.userId)
            ?: throw AppException(404, "User tidak valid!")

        val authToken       = buildJwt(user.id)
        val strRefreshToken = UUID.randomUUID().toString()
        refreshTokenRepository.create(
            RefreshToken(userId = user.id, authToken = authToken, refreshToken = strRefreshToken)
        )

        call.respond(
            DataResponse(
                "success",
                "Berhasil melakukan refresh token",
                mapOf("authToken" to authToken, "refreshToken" to strRefreshToken),
            )
        )
    }

    // ── POST /auth/logout ─────────────────────────────────────────────────────
    suspend fun postLogout(call: ApplicationCall) {
        val request = call.receive<RefreshTokenRequest>()

        val validator = ValidatorHelper(request.toMap())
        validator.required("authToken", "Auth Token tidak boleh kosong")
        validator.validate()

        val decoded = JWT.require(Algorithm.HMAC256(jwtSecret))
            .build()
            .verify(request.authToken)

        val userId = decoded.getClaim("userId").asString()
            ?: throw AppException(401, "Token tidak valid")

        refreshTokenRepository.delete(request.authToken)
        refreshTokenRepository.deleteByUserId(userId)

        call.respond(DataResponse("success", "Berhasil logout", null))
    }

    // ── Helper ────────────────────────────────────────────────────────────────
    private fun buildJwt(userId: String): String =
        JWT.create()
            .withAudience(JWTConstants.AUDIENCE)
            .withIssuer(JWTConstants.ISSUER)
            .withClaim("userId", userId)
            .withExpiresAt(Date(System.currentTimeMillis() + 60 * 60 * 1000)) // 1 hour
            .sign(Algorithm.HMAC256(jwtSecret))
}