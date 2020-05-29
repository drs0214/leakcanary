package androidx.test.orchestrator.instrumentationlistener

import android.os.Bundle
import androidx.test.orchestrator.callback.OrchestratorCallback
import androidx.test.orchestrator.listeners.OrchestrationListenerManager
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.KEY_TEST_EVENT
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.TestEvent
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.TestEvent.TEST_FINISHED
import shark.SharkLog
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

internal fun OrchestratedInstrumentationListener.delayFinished(
  onFinishReceived: ((Boolean) -> Unit) -> Unit
) {
  val realCallback = odoCallback
  odoCallback = object : OrchestratorCallback by realCallback {
    override fun sendTestNotification(bundle: Bundle) {
      if (bundle.getString(KEY_TEST_EVENT) == TEST_FINISHED.toString()) {
        val keepFinished = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        onFinishReceived { keep ->
          keepFinished.set(keep)
          SharkLog.d { "Unblocking thread" }
          latch.countDown()
        }
        SharkLog.d { "Blocking thread on finished" }
        latch.await()
        if (keepFinished.get()) {
          SharkLog.d { "Forwarding finished" }
          realCallback.sendTestNotification(bundle)
        } else {
          SharkLog.d { "Skipped finished" }
        }
        return
      }
      SharkLog.d { "Forwarded ${bundle.getString(KEY_TEST_EVENT)}" }
      realCallback.sendTestNotification(bundle)
    }
  }
}