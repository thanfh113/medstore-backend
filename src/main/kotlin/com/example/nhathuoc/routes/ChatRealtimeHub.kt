package com.example.nhathuoc.routes

import com.example.nhathuoc.service.ChatMessageDto
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.Frame
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

object ChatRealtimeHub {
    private val json = Json { ignoreUnknownKeys = true }
    private val sessionMutex = Mutex()
    private val connections = ConcurrentHashMap<String, MutableSet<DefaultWebSocketServerSession>>()

    suspend fun register(sessionId: String, socket: DefaultWebSocketServerSession) {
        sessionMutex.withLock {
            val room = connections.getOrPut(sessionId) { linkedSetOf() }
            room.add(socket)
        }
    }

    suspend fun unregister(sessionId: String, socket: DefaultWebSocketServerSession) {
        sessionMutex.withLock {
            connections[sessionId]?.remove(socket)
            if (connections[sessionId].isNullOrEmpty()) {
                connections.remove(sessionId)
            }
        }
    }

    suspend fun broadcast(sessionId: String, message: ChatMessageDto) {
        val payload = json.encodeToString(message)
        val sockets = sessionMutex.withLock {
            connections[sessionId]?.toList().orEmpty()
        }

        val failedSockets = mutableListOf<DefaultWebSocketServerSession>()
        sockets.forEach { socket ->
            runCatching {
                socket.send(Frame.Text(payload))
            }.onFailure {
                failedSockets += socket
            }
        }

        if (failedSockets.isNotEmpty()) {
            sessionMutex.withLock {
                connections[sessionId]?.removeAll(failedSockets.toSet())
                if (connections[sessionId].isNullOrEmpty()) {
                    connections.remove(sessionId)
                }
            }
        }
    }
}
