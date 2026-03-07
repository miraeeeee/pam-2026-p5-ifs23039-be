package org.delcom

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.statuspages.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.delcom.data.AppException
import org.delcom.data.ErrorResponse
import org.delcom.helpers.JWTConstants
import org.delcom.helpers.parseMessageToMap
import org.delcom.services.AuthService
import org.delcom.services.TodoService
import org.delcom.services.UserService
import org.koin.ktor.ext.inject

fun Application.configureRouting() {
    val authService : AuthService by inject()
    val userService : UserService by inject()
    val todoService : TodoService by inject()

    install(StatusPages) {
        exception<AppException> { call, cause ->
            val dataMap = parseMessageToMap(cause.message)
            call.respond(
                status  = HttpStatusCode.fromValue(cause.code),
                message = ErrorResponse(
                    status  = "fail",
                    message = if (dataMap.isEmpty()) cause.message else "Data yang dikirimkan tidak valid!",
                    data    = if (dataMap.isEmpty()) null else dataMap.toString(),
                )
            )
        }

        exception<Throwable> { call, cause ->
            call.respond(
                status  = HttpStatusCode.InternalServerError,
                message = ErrorResponse(
                    status  = "error",
                    message = cause.message ?: "Unknown error",
                    data    = null,
                )
            )
        }
    }

    routing {
        get("/") { call.respondText("API telah berjalan.") }

        // ── Auth ──────────────────────────────────────────────────────────────
        route("/auth") {
            post("/register")      { authService.postRegister(call) }
            post("/login")         { authService.postLogin(call) }
            post("/refresh-token") { authService.postRefreshToken(call) }
            post("/logout")        { authService.postLogout(call) }
        }

        // ── Protected routes ──────────────────────────────────────────────────
        authenticate(JWTConstants.NAME) {

            // Users
            route("/users") {
                get("/me")           { userService.getMe(call) }
                put("/me")           { userService.putMe(call) }          // name, username, bio
                put("/me/password")  { userService.putMyPassword(call) }
                put("/me/photo")     { userService.putMyPhoto(call) }
            }

            // Todos
            route("/todos") {
                get            { todoService.getAll(call) }    // ?search ?is_done ?urgency ?page ?perPage
                get("/stats")  { todoService.getStats(call) }
                post           { todoService.post(call) }
                get("/{id}")   { todoService.getById(call) }
                put("/{id}")   { todoService.put(call) }
                put("/{id}/cover") { todoService.putCover(call) }
                delete("/{id}") { todoService.delete(call) }
            }
        }

        // ── Public image serving ──────────────────────────────────────────────
        route("/images") {
            get("/users/{id}") { userService.getPhoto(call) }
            get("/todos/{id}") { todoService.getCover(call) }
        }
    }
}