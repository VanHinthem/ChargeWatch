package nl.vanhinthem.chargewatch.data.ndw

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class ViewportRequestCoordinator(
    private val scope: CoroutineScope,
    private val debounceMillis: Long = 500,
    private val loader: suspend (NdwViewport) -> Unit
) {
    private var pending: Job? = null
    private var lastViewport: NdwViewport? = null

    fun submit(viewport: NdwViewport) {
        val bounded = viewport.bounded()
        if (bounded == lastViewport) return
        pending?.cancel()
        pending = scope.launch {
            delay(debounceMillis)
            lastViewport = bounded
            loader(bounded)
        }
    }

    fun cancel() {
        pending?.cancel()
        pending = null
    }
}
