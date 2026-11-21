package org.nemack.universalfilelab.etroute.runtimepack

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.nemack.universalfilelab.etroute.FixedProbeEvidenceSession

// Private child Activity. The completion future is resolved after worker/native finalization, not on Activity result.
internal object ChainLifecycleBridge {
    class Slot {
        val token = UUID.randomUUID().toString()
        val done = CompletableFuture<String>()
        val destroyed = CountDownLatch(1)
        val stopped = AtomicBoolean(false)
        val attached = AtomicBoolean(false)
        @Volatile var owner: FixedProbeEvidenceSession? = null
        @Volatile var finish: (() -> Unit)? = null
        fun stop() { stopped.set(true); owner?.stop(); finish?.invoke() }
    }
    val active = AtomicReference<Slot?>(null)
}

class ChainLifecycleActivity : Activity() {
    private var slot: ChainLifecycleBridge.Slot? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val current = ChainLifecycleBridge.active.get()
        if (current == null || intent.getStringExtra("chainToken") != current.token ||
            !current.attached.compareAndSet(false, true)) { finish(); return }
        slot = current
        current.finish = { runOnUiThread { if (!isFinishing) finish() } }
        setContentView(TextView(this).apply {
            text = "Lifecycle test in progress. This child screen will close after readiness. The chain waits for native termination before advancing."
            setPadding(24, 48, 24, 24)
        })
        Thread {
            val report = try {
                check(!current.stopped.get()) { "Chain stopped before lifecycle preparation" }
                val candidate = Candidate.installAndInspect(applicationContext)
                val session = FixedProbeEvidenceSession(applicationContext, candidate.spec, candidate.root)
                current.owner = session
                if (current.stopped.get()) session.onDestroyed()
                session.run(true, approved = { !current.stopped.get() }, onReadyDestroy = {
                    current.finish?.invoke()
                }).also {
                    if (it.startsWith("ST08_LIFECYCLE=PASS")) check(current.destroyed.await(5, TimeUnit.SECONDS)) {
                        "Activity destruction did not finish"
                    }
                }
            } catch (error: Throwable) {
                "LIFECYCLE_ERROR ${error::class.java.simpleName}: ${error.message?.take(1024)}"
            } finally {
                current.owner?.stop()
                current.finish?.invoke()
            }
            current.finish = null
            current.done.complete(report)
        }.start()
    }
    override fun onDestroy() {
        slot?.owner?.onDestroyed()
        // Destruction before attachment must remain cancelled when a late owner is created.
        slot?.stopped?.set(true)
        super.onDestroy()
        slot?.destroyed?.countDown()
    }
}
