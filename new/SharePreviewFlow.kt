package jp.muya.xsaver

/** One pending share in memory. Request IDs prevent old/unrelated results from auto-playing. */
class SharePreviewFlow {
    data class Request(val url: String, val id: String = java.util.UUID.randomUUID().toString())
    private enum class Phase { QUEUED, LOADING, READY }
    private var request: Request? = null
    private var phase = Phase.QUEUED
    val pendingUrl: String? get() = request?.url
    fun accept(url: String) { request = Request(url); phase = Phase.QUEUED }
    fun cancel() { request = null; phase = Phase.QUEUED }
    fun acceptsResult(id: String?) = request == null || request?.id == id
    fun nextLoad(): Request? = request?.takeIf { phase == Phase.QUEUED }?.also { phase = Phase.LOADING }
    fun complete(id: String?, url: String?, canPreview: Boolean) {
        val current = request ?: return
        if (phase != Phase.LOADING || id != current.id) return
        if (url == current.url && canPreview) phase = Phase.READY else cancel()
    }
    fun takePreview(url: String): Boolean {
        if (phase != Phase.READY || request?.url != url) return false
        cancel(); return true
    }
}
