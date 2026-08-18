package io.sentry.buddy.endpoints.flow

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class FlowAnalysisStore(private val baseDir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val locks = ConcurrentHashMap<String, Mutex>()

    /**
     * Serializes load-modify-save for one flow. The analysis pipeline and a resolve call write the
     * same file, and a resolve holds a network round-trip between its load and its save, thus
     * without this lock one of the two writes is lost.
     */
    suspend fun <T> withFlowLock(flowId: String, block: suspend () -> T): T =
        locks.computeIfAbsent(flowId) { Mutex() }.withLock { block() }

    private fun flowAnalysisDir(flowId: String): File = File(baseDir, flowId)

    private fun createdFlowAnalysisDir(flowId: String): File = flowAnalysisDir(flowId).apply { mkdirs() }

    fun saveRequest(request: FlowAnalysisRequest) {
        File(createdFlowAnalysisDir(request.flowId), "request.json")
            .writeText(json.encodeToString(FlowAnalysisRequest.serializer(), request))
    }

    fun loadRequest(flowId: String): FlowAnalysisRequest? {
        val file = File(flowAnalysisDir(flowId), "request.json")
        if (!file.exists()) return null
        return json.decodeFromString(FlowAnalysisRequest.serializer(), file.readText())
    }

    fun saveResult(response: FlowAnalysisResponse) {
        File(createdFlowAnalysisDir(response.flowId), "result.json")
            .writeText(json.encodeToString(FlowAnalysisResponse.serializer(), response))
    }

    fun loadResult(flowId: String): FlowAnalysisResponse? {
        val file = File(flowAnalysisDir(flowId), "result.json")
        if (!file.exists()) return null
        return json.decodeFromString(FlowAnalysisResponse.serializer(), file.readText())
    }
}
