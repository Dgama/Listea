package com.example.listea

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/**
 * What [Exporter.deliver] owes the thread it was called on.
 *
 * The one contract here that is not about bytes: a delivery is a socket or a file, and every caller
 * in this app reaches it from the main thread — a checkbox tap, a swipe, a button. An implementation
 * that opened its connection there would not deliver slowly, it would not deliver at all:
 * `NetworkOnMainThreadException` on every round.
 *
 * **This is the bug v0.5.6 shipped and the device pass found.** Before the release the single call
 * to `postWebhook` was wrapped in `withContext(ioDispatcher)` at its call site. Moving it behind
 * `WebhookExporter` moved it out of that wrapper, and the exception above is what the phone said on
 * the first round that reached a real URL. Nothing in this suite could have said it: the tests here
 * are pure, and there is no thread policy to violate under a plain JVM.
 *
 * So what is pinned is not the exception — that cannot be reproduced off device — but the one
 * mechanical fact that decides it: **the work is handed to a dispatcher.** A delivery that never
 * enters [CoroutineDispatcher.dispatch] is a delivery that ran on the caller's thread, which is the
 * regression, stated without needing Android to complain about it.
 */
class ExporterDeliveryTest {

    /**
     * A dispatcher that runs what it is given on the calling thread and records that it was asked.
     *
     * Running inline keeps the test deterministic and free of a second thread; recording is the
     * assertion. `Dispatchers.IO` would do neither — a test cannot observe which thread a coroutine
     * landed on, and a dispatcher nothing enters is indistinguishable from one that was never
     * consulted at all.
     */
    private class RecordingDispatcher(private val entered: AtomicBoolean) : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            entered.set(true)
            block.run()
        }
    }

    private fun payload() = ExportPayload(
        event = EVENT_LIST_COMPLETED,
        listId = 1L,
        listTitle = "Demo",
        completedAt = 0L,
        sourceId = null,
        sourceTitle = null,
        at = 0L,
        items = emptyList()
    )

    /**
     * The regression, in one assertion.
     *
     * The URL is a closed port on the loopback rather than a name to resolve: this asserts a thread
     * policy, and a delivery that has to go out over DNS to fail would make the test about the
     * network. Refusing the connection immediately is the fastest honest way to reach the code
     * under test — and that it comes back as a result rather than an exception is the second
     * guarantee, asserted below.
     */
    @Test
    fun `a delivery is handed to a dispatcher rather than run on the caller's thread`() = runBlocking {
        val entered = AtomicBoolean(false)

        val attempt = WebhookExporter("http://127.0.0.1:1/hook", RecordingDispatcher(entered))
            .deliver(payload(), "{}")

        assertTrue(
            "the delivery never left the calling thread, which on a device is the main one",
            entered.get()
        )
        assertTrue(
            "an unreachable receiver is a failure the user is told about, not an exception",
            attempt is ExportAttempt.Failed
        )
    }
}
