package io.sentry.buddy.flow

import io.sentry.buddy.FlowAnalysisRequest
import io.sentry.buddy.FlowAnalysisResponse
import kotlinx.serialization.json.Json
import java.io.File

class FlowAnalysisStore(private val baseDir: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private fun flowAnalysisDir(flowId: String): File = File(baseDir, flowId).apply { mkdirs() }

    fun saveRequest(request: FlowAnalysisRequest) {
        File(flowAnalysisDir(request.flowId), "request.json")
            .writeText(json.encodeToString(FlowAnalysisRequest.serializer(), request))
    }

    fun loadRequest(flowId: String): FlowAnalysisRequest? {
        val file = File(flowAnalysisDir(flowId), "request.json")
        if (!file.exists()) return null
        return json.decodeFromString(FlowAnalysisRequest.serializer(), file.readText())
    }

    fun saveResult(response: FlowAnalysisResponse) {
        File(flowAnalysisDir(response.flowId), "result.json")
            .writeText(json.encodeToString(FlowAnalysisResponse.serializer(), response))
    }

    fun loadResult(flowId: String): FlowAnalysisResponse? {
        val file = File(flowAnalysisDir(flowId), "result.json")
        if (!file.exists()) return null
        return json.decodeFromString(FlowAnalysisResponse.serializer(), file.readText())
    }
}
