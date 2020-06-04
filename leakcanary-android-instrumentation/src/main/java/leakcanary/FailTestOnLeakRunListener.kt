/*
 * Copyright (C) 2018 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package leakcanary

import android.app.Instrumentation
import android.os.Bundle
import android.os.RemoteException
import android.util.Log
import androidx.test.internal.runner.listener.InstrumentationResultPrinter
import androidx.test.internal.runner.listener.InstrumentationResultPrinter.REPORT_VALUE_RESULT_FAILURE
import androidx.test.orchestrator.instrumentationlistener.OrchestratedInstrumentationListener
import androidx.test.orchestrator.instrumentationlistener.delayFinished
import androidx.test.orchestrator.junit.BundleJUnitUtils
import androidx.test.orchestrator.junit.ParcelableFailure
import androidx.test.orchestrator.listeners.OrchestrationListenerManager
import androidx.test.orchestrator.listeners.OrchestrationListenerManager.TestEvent.TEST_FAILURE
import androidx.test.platform.app.InstrumentationRegistry.getInstrumentation
import androidx.test.runner.AndroidJUnitRunner
import leakcanary.InstrumentationLeakDetector.Result.AnalysisPerformed
import org.junit.runner.Description
import org.junit.runner.Result
import org.junit.runner.notification.Failure
import org.junit.runner.notification.RunListener
import shark.HeapAnalysis
import shark.HeapAnalysisFailure
import shark.HeapAnalysisSuccess
import shark.SharkLog

/**
 *
 * A JUnit [RunListener] that uses [InstrumentationLeakDetector] to detect memory leaks in Android
 * instrumentation tests. It waits for the end of a test, and if the test succeeds then it will
 * look for retained objects, trigger a heap dump if needed and perform an analysis.
 *
 *  [FailTestOnLeakRunListener] can be subclassed to override [skipLeakDetectionReason] and
 *  [onAnalysisPerformed]
 *
 * @see InstrumentationLeakDetector
 */
open class FailTestOnLeakRunListener : RunListener() {
  private var currentTestDescription: Description? = null
  private var skipLeakDetectionReason: String? = null

  @Volatile
  private var finishTrigger: ((Bundle?) -> Unit)? = null
  @Volatile
  private var result: Bundle? = null
  @Volatile
  private var hasResult: Boolean = false

  override fun testStarted(description: Description) {
    currentTestDescription = description
    skipLeakDetectionReason = skipLeakDetectionReason(description)
    if (skipLeakDetectionReason != null) {
      return
    }
  }

  /**
   * Can be overridden to skip leak detection based on the description provided when a test
   * is started. Return null to continue leak detection, or a string describing the reason for
   * skipping otherwise.
   */
  protected open fun skipLeakDetectionReason(description: Description): String? {
    return null
  }

  override fun testFailure(failure: Failure) {
    skipLeakDetectionReason = "failed"
  }

  override fun testIgnored(description: Description) {
    skipLeakDetectionReason = "was ignored"
  }

  override fun testAssumptionFailure(failure: Failure) {
    skipLeakDetectionReason = "had an assumption failure"
  }

  override fun testFinished(description: Description) {
    SharkLog.d { "Received testFinished in FailTestOnLeakRunListener" }
    detectLeaks()
    AppWatcher.objectWatcher.clearWatchedObjects()
    currentTestDescription = null
  }

  override fun testRunStarted(description: Description) {
    InstrumentationLeakDetector.updateConfig()

    val instrumentation = getInstrumentation()

    if (instrumentation is AndroidJUnitRunner) {
      val orchestratorListenerField =
        AndroidJUnitRunner::class.java.getDeclaredField("orchestratorListener")
      orchestratorListenerField.isAccessible = true
      val orchestratorListener =
        orchestratorListenerField.get(instrumentation) as OrchestratedInstrumentationListener?
      orchestratorListener?.delayFinished { triggerFinish ->
        if (hasResult) {
          val localResult = result
          result = null
          hasResult = false
          triggerFinish(localResult)
        } else {
          finishTrigger = triggerFinish
        }
      }
    }
  }

  override fun testRunFinished(result: Result) {
  }

  private fun detectLeaks() {
    if (skipLeakDetectionReason != null) {
      forwardFinish(null)
      SharkLog.d { "Skipping leak detection because the test $skipLeakDetectionReason" }
      skipLeakDetectionReason = null
      return
    }

    val leakDetector = InstrumentationLeakDetector()
    SharkLog.d { "Detecting leaks" }
    val result = leakDetector.detectLeaks()
    SharkLog.d { "Done detecting leaks, result is $result" }

    if (result is AnalysisPerformed) {
      onAnalysisPerformed(heapAnalysis = result.heapAnalysis)
    } else {
      forwardFinish(null)
    }
  }

  /**
   * Called when a heap analysis has been performed and a result is available.
   *
   * The default implementation call [failTest] if the [heapAnalysis] failed or if
   * [HeapAnalysisSuccess.applicationLeaks] is not empty.
   */
  protected open fun onAnalysisPerformed(heapAnalysis: HeapAnalysis) {
    SharkLog.d { "Done: $heapAnalysis" }
    when (heapAnalysis) {
      is HeapAnalysisFailure -> {
        // TODO This should be the exception
        failTest(Log.getStackTraceString(heapAnalysis.exception))
      }
      is HeapAnalysisSuccess -> {
        val applicationLeaks = heapAnalysis.applicationLeaks
        if (applicationLeaks.isNotEmpty()) {
          failTest("Test failed because application memory leaks were detected:\n$heapAnalysis")
        } else {
          forwardFinish(null)
        }
      }
    }
  }

  /**
   * Reports that the test has failed, with the provided [message].
   */
  protected fun failTest(message: String) {
    val description = currentTestDescription!!
    val instrumentation = getInstrumentation()

    // TODO All this only needs to happen once.
    if (instrumentation is AndroidJUnitRunner) {
      val orchestratorListenerField =
        AndroidJUnitRunner::class.java.getDeclaredField("orchestratorListener")
      orchestratorListenerField.isAccessible = true
      val orchestratorListener =
        orchestratorListenerField.get(instrumentation) as OrchestratedInstrumentationListener?
      if (orchestratorListener != null) {
        try {
          val failure = Failure(description, RuntimeException(message))
          SharkLog.d { "Sending result" }
          val bundle = BundleJUnitUtils.getBundleFromFailure(failure)
          bundle.putString(OrchestrationListenerManager.KEY_TEST_EVENT, TEST_FAILURE.toString())
          forwardFinish(bundle)
          return
//          exitProcess(1)
        } catch (e: RemoteException) {
          throw IllegalStateException("Unable to send TestFailure status, terminating", e)
        }
      }
    }

    val testClass = description.className
    val testName = description.methodName

    val bundle = Bundle()
    bundle.putString(
        Instrumentation.REPORT_KEY_IDENTIFIER, FailTestOnLeakRunListener::class.java.name
    )
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_NAME_CLASS, testClass)
    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_NAME_TEST, testName)

    bundle.putString(InstrumentationResultPrinter.REPORT_KEY_STACK, message)
    instrumentation.sendStatus(REPORT_VALUE_RESULT_FAILURE, bundle)
  }

  private fun forwardFinish(result: Bundle?) {
    val trigger = finishTrigger
    if (trigger != null) {
      SharkLog.d { "Forward finish $result with $finishTrigger" }
      trigger(result)
    } else {
      SharkLog.d { "No forward yet, storing result $result" }
      this.result = result
      hasResult = true
    }
  }
}
