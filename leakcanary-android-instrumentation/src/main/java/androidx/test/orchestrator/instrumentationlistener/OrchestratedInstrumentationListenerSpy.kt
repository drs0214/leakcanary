package androidx.test.orchestrator.instrumentationlistener

import android.os.Bundle
import androidx.test.orchestrator.callback.OrchestratorCallback
import androidx.test.orchestrator.junit.ParcelableFailure
import androidx.test.orchestrator.junit.ParcelableResult
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.KEY_TEST_EVENT
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.TestEvent
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.TestEvent.TEST_FINISHED
import org.junit.runner.notification.Failure
import shark.SharkLog
import java.io.PrintWriter
import java.io.StringWriter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference

internal fun OrchestratedInstrumentationListener.delayFinished(
  onFinishReceived: ((Bundle?) -> Unit) -> Unit
) {
  val realCallback = odoCallback
  val failures = mutableListOf<ParcelableFailure>()
  odoCallback = object : OrchestratorCallback by realCallback {
    override fun sendTestNotification(bundle: Bundle) {
      if (bundle.getString(KEY_TEST_EVENT) == TEST_FINISHED.toString()) {
        SharkLog.d { "Received finished $bundle" }
        val keepFinished = AtomicReference<Bundle?>(null)
        val latch = CountDownLatch(1)
        onFinishReceived { keep ->
          keepFinished.set(keep)
          SharkLog.d { "Unblocking thread" }
          latch.countDown()
        }
        if (latch.count > 0) {
          SharkLog.d { "Blocking thread on finished" }
          latch.await()
        }
        val replacementBundle = keepFinished.get()
        if (replacementBundle == null) {
          SharkLog.d { "Forwarding finished $bundle" }
          realCallback.sendTestNotification(bundle)
        } else {
          SharkLog.d { "Skipped finished, sending replacement $replacementBundle" }
          failures += replacementBundle.get("failure") as ParcelableFailure
          realCallback.sendTestNotification(replacementBundle)
          // Need to then send proper finish
          realCallback.sendTestNotification(bundle)
        }
        return
      }

      if (bundle.getString(KEY_TEST_EVENT) == TestEvent.TEST_FAILURE.toString()) {
        val parcelableFailure = bundle.get("failure") as ParcelableFailure

        val newTrace = RuntimeException("Yeah").run {
          val stringWriter = StringWriter()
          val writer = PrintWriter(stringWriter)
          printStackTrace(writer)
          stringWriter.toString()
        }

        bundle.putParcelable(
            "failure", ParcelableFailure(parcelableFailure.description, RuntimeException(newTrace))
        )
      }

      if (bundle.getString(KEY_TEST_EVENT) == TestEvent.TEST_RUN_FINISHED.toString()) {
        if (failures.isNotEmpty()) {
          val result = bundle.get("result") as ParcelableResult
          result.failures += failures
        }
      }

      SharkLog.d { "Forwarded ${bundle.getString(KEY_TEST_EVENT)}: $bundle" }
      realCallback.sendTestNotification(bundle)
    }
  }
}