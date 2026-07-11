package dev.androidagent.accessibility

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import dev.androidagent.OverlayController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.UUID
import kotlin.coroutines.resume

class AccessibilityCommandExecutor internal constructor(
    private val context: Context,
    private val overlayController: OverlayController?,
    private val approvalCapabilities: ApprovalCapabilityStore = ApprovalCapabilityStore(),
    private val onPhoneControlCommandStarted: (String) -> Unit = {},
    private val onPhoneControlCommandFinished: (String) -> Unit = {}
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private val observer = ScreenObserver()
    private val commandActor = PhoneCommandActor { invocation ->
        onPhoneControlCommandStarted(invocation.command)
        try {
            executeInternal(
                invocation.command,
                invocation.args,
                invocation.ownerId,
                invocation.approvalCapability
            )
        } finally {
            onPhoneControlCommandFinished(invocation.command)
        }
    }

    suspend fun executeSuspending(
        commandId: String,
        command: String,
        args: JSONObject,
        requestOwner: String = LEGACY_REQUEST_OWNER,
        approvalCapability: String? = null
    ): CommandResult = commandActor.execute(
        PhoneCommandInvocation(commandId, requestOwner, command, args, approvalCapability)
    )

    fun execute(
        command: String,
        args: JSONObject,
        requestOwner: String = LEGACY_REQUEST_OWNER,
        approvalCapability: String? = null,
        commandId: String = "android_${UUID.randomUUID()}",
        callback: (CommandResult) -> Unit
    ) {
        scope.launch {
            val result = executeSuspending(commandId, command, args, requestOwner, approvalCapability)
            callback(result)
        }
    }

    fun cancelApprovals(requestOwner: String) {
        approvalCapabilities.cancelOwner(requestOwner)
        commandActor.cancelOwner(requestOwner)
    }

    fun cancelApprovalsForPrefix(prefix: String) {
        approvalCapabilities.cancelOwnerPrefix(prefix)
        commandActor.cancelOwnerPrefix(prefix)
    }

    fun cancelCommand(commandId: String, requestOwner: String? = null, reason: String = PhoneCommandActor.COMMAND_CANCELLED) {
        commandActor.cancelCommand(commandId, requestOwner, reason)
    }

    fun clearApprovals() {
        approvalCapabilities.clear()
    }

    fun close() {
        approvalCapabilities.clear()
        commandActor.close()
        scope.cancel()
        overlayController?.dismissConfirmation()
        observer.clearNodes()
    }

    private suspend fun executeInternal(
        command: String,
        args: JSONObject,
        requestOwner: String,
        approvalCapability: String?
    ): CommandResult {
        val service = PhoneAccessibilityService.instance
        authorizationFailure(command, args, requestOwner, approvalCapability, service)?.let { return it }

        return when (command) {
            "observe_screen" -> withAgentChromeSuppressed {
                CommandResult(true, observer.observe(requireService(service)))
            }
            "open_app" -> withAgentChromeSuppressed {
                val targetPackage = openApp(args)
                waitMs(900)
                val observation = service?.let { observer.observe(it) }
                    ?: JSONObject().put("screenSummary", "App opened; accessibility service is not enabled")
                val observedPackage = observation.optString("package")
                if (observedPackage.isNotBlank() && observedPackage != targetPackage) {
                    CommandResult(false, observation, "Requested $targetPackage, but foreground package is $observedPackage")
                } else {
                    CommandResult(true, observation)
                }
            }
            "tap_node" -> {
                service ?: return accessibilityMissing()
                withAgentChromeSuppressed {
                    val node = requireNode(args.getString("nodeId"))
                    tapNode(service, node)
                    waitMs(180)
                    CommandResult(true, observer.observe(service))
                }
            }
            "tap_xy" -> {
                service ?: return accessibilityMissing()
                withAgentChromeSuppressed {
                    tap(service, args.getDouble("x").toFloat(), args.getDouble("y").toFloat())
                    waitMs(180)
                    CommandResult(true, observer.observe(service))
                }
            }
            "tap_normalized" -> {
                service ?: return accessibilityMissing()
                withAgentChromeSuppressed {
                    tapNormalized(service, args.getDouble("xPct").toFloat(), args.getDouble("yPct").toFloat())
                    waitMs(180)
                    CommandResult(true, observer.observe(service))
                }
            }
            "long_press_node" -> {
                service ?: return accessibilityMissing()
                withAgentChromeSuppressed {
                    val node = requireNode(args.getString("nodeId"))
                    longPressNode(service, node)
                    waitMs(250)
                    CommandResult(true, observer.observe(service))
                }
            }
            "type_text" -> {
                service ?: return accessibilityMissing()
                withAgentChromeSuppressed {
                    typeText(service, args.getString("text"))
                    waitMs(180)
                    CommandResult(true, observer.observe(service))
                }
            }
            "scroll" -> {
                service ?: return accessibilityMissing()
                withAgentChromeSuppressed {
                    scroll(service, args.optString("direction", "down"))
                    waitMs(250)
                    CommandResult(true, observer.observe(service))
                }
            }
            "swipe" -> {
                service ?: return accessibilityMissing()
                withAgentChromeSuppressed {
                    swipe(
                        service,
                        args.getDouble("startX").toFloat(),
                        args.getDouble("startY").toFloat(),
                        args.getDouble("endX").toFloat(),
                        args.getDouble("endY").toFloat(),
                        args.optLong("durationMs", 350L)
                    )
                    waitMs(220)
                    CommandResult(true, observer.observe(service))
                }
            }
            "press_back" -> withAgentChromeSuppressed { global(requireService(service), AccessibilityService.GLOBAL_ACTION_BACK) }
            "press_home" -> withAgentChromeSuppressed { global(requireService(service), AccessibilityService.GLOBAL_ACTION_HOME) }
            "open_recents" -> withAgentChromeSuppressed { global(requireService(service), AccessibilityService.GLOBAL_ACTION_RECENTS) }
            "take_screenshot" -> takeScreenshot(requireService(service))
            "submit_text" -> {
                service ?: return accessibilityMissing()
                withAgentChromeSuppressed {
                    submitFocusedText(service)
                    waitMs(700)
                    CommandResult(true, observer.observe(service))
                }
            }
            "ask_user_confirmation" -> askUserConfirmation(service, args, requestOwner)
            "wait" -> {
                waitMs(args.optLong("ms", 1000L))
                CommandResult(true, service?.let { observer.observe(it) } ?: JSONObject().put("screenSummary", "Wait completed; accessibility service is not enabled"))
            }
            else -> CommandResult(false, service?.let { observer.observe(it) }, "Unknown command: $command")
        }
    }

    private fun authorizationFailure(
        command: String,
        args: JSONObject,
        requestOwner: String,
        approvalCapability: String?,
        service: PhoneAccessibilityService?
    ): CommandResult? {
        if (!PhoneCommandPolicy.requiresApproval(command)) return null
        val action = PhoneActionDescriptor.create(command, args)
        val validation = approvalCapabilities.validateAndConsume(
            token = approvalCapability,
            ownerId = requestOwner,
            action = action,
            observationContext = currentApprovalContext(service)
        )
        val error = validation.denialMessage(action.summary) ?: return null
        return CommandResult(false, observer.observationSnapshot(), error)
    }

    private fun openApp(args: JSONObject): String {
        val packageName = args.optString("packageName").takeIf { it.isNotBlank() }
            ?: findPackageByLabel(args.optString("appName"))
            ?: throw IllegalArgumentException("No packageName or matching appName supplied")
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalArgumentException("No launch intent for $packageName")
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        return packageName
    }

    private fun findPackageByLabel(appName: String): String? {
        if (appName.isBlank()) return null
        val needle = appName.lowercase()
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val launchableApps = context.packageManager.queryIntentActivities(launcherIntent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
        return launchableApps
            .firstOrNull { app -> context.packageManager.getApplicationLabel(app).toString().equals(appName, ignoreCase = true) }
            ?.packageName
            ?: launchableApps
                .firstOrNull { app -> context.packageManager.getApplicationLabel(app).toString().lowercase().contains(needle) }
                ?.packageName
    }

    private suspend fun tapNode(service: PhoneAccessibilityService, node: AccessibilityNodeInfo) {
        val target = node.clickableSelfOrParent() ?: node
        if (target.isClickable && target.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return
        }
        val rect = Rect()
        target.getBoundsInScreen(rect)
        tap(service, rect.centerX().toFloat(), rect.centerY().toFloat())
    }

    private suspend fun longPressNode(service: PhoneAccessibilityService, node: AccessibilityNodeInfo) {
        if (node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            return
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        gesture(service, rect.centerX().toFloat(), rect.centerY().toFloat(), rect.centerX().toFloat(), rect.centerY().toFloat(), 800)
    }

    private suspend fun typeText(service: PhoneAccessibilityService, text: String) {
        val target = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: service.rootInActiveWindow?.findFirstEditable()
            ?: throw IllegalStateException("No focused or editable text field found")

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        if (target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
            return
        }

        if (pasteText(service, target, text)) {
            return
        }

        throw IllegalStateException("Focused field accepted neither ACTION_SET_TEXT nor ACTION_PASTE")
    }

    private suspend fun submitFocusedText(service: PhoneAccessibilityService) {
        val target = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: service.rootInActiveWindow?.findFirstEditable()
        if (target != null) {
            val imeEnter = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            } else {
                false
            }
            if (imeEnter) return
        }
        tapNormalized(service, 0.92f, 0.93f)
    }

    private suspend fun pasteText(service: PhoneAccessibilityService, target: AccessibilityNodeInfo, text: String): Boolean {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val previousClip = runCatching { clipboard.primaryClip }.getOrNull()
        val hadPreviousClip = runCatching { clipboard.hasPrimaryClip() }.getOrDefault(false)

        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val rect = Rect()
        target.getBoundsInScreen(rect)
        if (!rect.isEmpty) {
            tap(service, rect.centerX().toFloat(), rect.centerY().toFloat())
            waitMs(150)
        }

        return try {
            clipboard.setPrimaryClip(ClipData.newPlainText("android-agent-text", text))
            val focused = service.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: target
            val pasted = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE) ||
                target.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            waitMs(150)
            pasted
        } finally {
            if (hadPreviousClip && previousClip != null) {
                clipboard.setPrimaryClip(previousClip)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard.clearPrimaryClip()
            }
        }
    }

    private suspend fun scroll(service: PhoneAccessibilityService, direction: String) {
        val root = service.rootInActiveWindow
        val scrollable = root?.findFirstScrollable()
        val action = when (direction) {
            "up", "left" -> AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
            else -> AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
        }
        if (scrollable?.performAction(action) == true) {
            return
        }
        val display = ScreenObserver.realDisplaySize(service)
        val midX = display.widthPx / 2f
        val midY = display.heightPx / 2f
        val delta = display.heightPx * 0.3f
        when (direction) {
            "up" -> swipe(service, midX, midY - delta, midX, midY + delta, 350)
            "left" -> swipe(service, midX - delta, midY, midX + delta, midY, 350)
            "right" -> swipe(service, midX + delta, midY, midX - delta, midY, 350)
            else -> swipe(service, midX, midY + delta, midX, midY - delta, 350)
        }
    }

    private suspend fun tap(service: PhoneAccessibilityService, x: Float, y: Float) {
        gesture(service, x, y, x, y, 80)
    }

    private suspend fun tapNormalized(service: PhoneAccessibilityService, xPct: Float, yPct: Float) {
        val x = xPct.coerceIn(0f, 1f)
        val y = yPct.coerceIn(0f, 1f)
        val display = ScreenObserver.realDisplaySize(service)
        tap(service, x * display.widthPx, y * display.heightPx)
    }

    private suspend fun swipe(service: PhoneAccessibilityService, startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        gesture(service, startX, startY, endX, endY, durationMs)
    }

    private suspend fun gesture(service: PhoneAccessibilityService, startX: Float, startY: Float, endX: Float, endY: Float, durationMs: Long) {
        withAgentChromeSuppressed {
            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()
            val ok = suspendCancellableCoroutine<Boolean> { continuation ->
                service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        continuation.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        continuation.resume(false)
                    }
                }, Handler(Looper.getMainLooper()))
            }
            if (!ok) {
                throw IllegalStateException("Gesture was cancelled")
            }
        }
    }

    private suspend fun global(service: PhoneAccessibilityService, action: Int): CommandResult {
        if (!service.performGlobalAction(action)) {
            return CommandResult(false, observer.observe(service), "Global action failed")
        }
        waitMs(250)
        return CommandResult(true, observer.observe(service))
    }

    private suspend fun takeScreenshot(service: PhoneAccessibilityService): CommandResult = withAgentChromeSuppressed {
        if (Build.VERSION.SDK_INT < 30) {
            return@withAgentChromeSuppressed CommandResult(false, observer.observe(service), "Screenshots require Android API 30+")
        }
        val encoded = suspendCancellableCoroutine<EncodedScreenshot?> { continuation ->
            val screenshotExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "OpenAgent-Screenshot").apply { isDaemon = true }
            }
            continuation.invokeOnCancellation {
                screenshotExecutor.shutdownNow()
            }
            try {
                service.takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    screenshotExecutor,
                    object : AccessibilityService.TakeScreenshotCallback {
                        override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                            screenshotExecutor.shutdown()
                            if (continuation.isActive) {
                                continuation.resume(encodeScreenshot(screenshot.hardwareBuffer, screenshot.colorSpace))
                            } else {
                                screenshot.hardwareBuffer.close()
                            }
                        }

                        override fun onFailure(errorCode: Int) {
                            screenshotExecutor.shutdown()
                            if (continuation.isActive) {
                                continuation.resume(null)
                            }
                        }
                    }
                )
            } catch (error: Throwable) {
                screenshotExecutor.shutdownNow()
                throw error
            }
        }
        if (encoded != null) {
            CommandResult(
                ok = true,
                observation = observer.observe(service),
                screenshotBase64 = encoded.base64,
                screenshot = JSONObject()
                    .put("widthPx", encoded.widthPx)
                    .put("heightPx", encoded.heightPx)
            )
        } else {
            CommandResult(false, observer.observe(service), "Screenshot capture failed")
        }
    }

    private suspend fun <T> withAgentChromeSuppressed(block: suspend () -> T): T {
        val controller = overlayController ?: return block()
        if (isOpenAgentActiveWindow()) {
            return block()
        }
        controller.suppressAgentChromeForAutomation()
        return try {
            waitMs(40)
            block()
        } finally {
            controller.restoreAgentChromeAfterAutomation()
        }
    }

    private fun isOpenAgentActiveWindow(): Boolean {
        val service = PhoneAccessibilityService.instance ?: return false
        val root = service.rootInActiveWindow ?: return false
        return try {
            root.packageName?.toString() == context.packageName
        } finally {
            root.recycle()
        }
    }

    private suspend fun askUserConfirmation(
        service: PhoneAccessibilityService?,
        args: JSONObject,
        requestOwner: String
    ): CommandResult {
        val targetCommand = args.optString("command").takeIf { it.isNotBlank() }
            ?: return CommandResult(false, observer.observationSnapshot(), "Confirmation requires the exact target command")
        if (!PhoneCommandPolicy.requiresApproval(targetCommand)) {
            return CommandResult(false, observer.observationSnapshot(), "$targetCommand does not require an approval capability")
        }
        val targetArgs = args.optJSONObject("args") ?: JSONObject()
        val action = PhoneActionDescriptor.create(targetCommand, targetArgs)
        val observation = observer.observationSnapshot() ?: service?.let { observer.observe(it) }
        val rationale = args.optString("message").takeIf { it.isNotBlank() }
        val preview = args.optString("preview").takeIf { it.isNotBlank() }
        val deferred = overlayController
            ?.askConfirmation(
                action.summary,
                listOfNotNull(rationale, preview).joinToString("\n\n").takeIf { it.isNotBlank() }
            )
        val confirmed = try {
            deferred?.let {
                val result = withTimeoutOrNull(CONFIRMATION_TIMEOUT_MS) { it.await() }
                if (result == null) overlayController.dismissConfirmation()
                result ?: false
            } ?: false
        } catch (error: CancellationException) {
            overlayController?.dismissConfirmation()
            throw error
        }
        val capability = approvalCapabilities.issueIfApproved(
            confirmed,
            requestOwner,
            action,
            approvalContextFromObservation(observation)
        )
            ?: return CommandResult(false, observation, "User denied or cancelled the action")
        return CommandResult(
            ok = true,
            observation = observation,
            approvalCapability = capability.token,
            approvalExpiresAtMs = capability.expiresAtMs,
            approvedAction = action.summary
        )
    }

    private fun requireService(service: PhoneAccessibilityService?): PhoneAccessibilityService {
        return service ?: throw IllegalStateException("Accessibility service is not enabled")
    }

    private fun accessibilityMissing(): CommandResult {
        return CommandResult(false, null, "Accessibility service is not enabled")
    }

    private fun encodeScreenshot(buffer: HardwareBuffer, colorSpace: android.graphics.ColorSpace): EncodedScreenshot? {
        return try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                return null
            }
            val bitmap = Bitmap.wrapHardwareBuffer(buffer, colorSpace)?.copy(Bitmap.Config.ARGB_8888, false) ?: return null
            val bytes = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 85, bytes)
            EncodedScreenshot(
                base64 = Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP),
                widthPx = bitmap.width,
                heightPx = bitmap.height
            )
        } finally {
            buffer.close()
        }
    }

    private fun currentObservationOrNull(): JSONObject? = PhoneAccessibilityService.instance?.let { observer.observe(it) }

    private fun requireNode(nodeId: String): AccessibilityNodeInfo {
        return observer.node(nodeId)
            ?: throw IllegalArgumentException("Node $nodeId is not present in the approved observation. Observe and request approval again.")
    }

    private fun currentApprovalContext(service: PhoneAccessibilityService?): ApprovalContext? {
        val observationId = observer.currentObservationId ?: return null
        val root = service?.rootInActiveWindow
        return try {
            ApprovalContext(
                observationId = observationId,
                packageName = root?.packageName?.toString().orEmpty(),
                activityName = service?.lastActivityClassName.orEmpty(),
                windowId = root?.windowId
            )
        } finally {
            root?.recycle()
        }
    }

    private fun approvalContextFromObservation(observation: JSONObject?): ApprovalContext? {
        val observationId = observation?.optString("observationId")?.takeIf { it.isNotBlank() } ?: return null
        return ApprovalContext(
            observationId = observationId,
            packageName = observation.optString("package"),
            activityName = observation.optString("activity"),
            windowId = observation.optInt("windowId").takeIf { observation.has("windowId") && !observation.isNull("windowId") }
        )
    }

    private suspend fun waitMs(ms: Long) {
        kotlinx.coroutines.delay(ms.coerceIn(0, 120_000))
    }

    private companion object {
        private const val CONFIRMATION_TIMEOUT_MS = 120_000L
        private const val LEGACY_REQUEST_OWNER = "legacy"
    }
}

private data class EncodedScreenshot(
    val base64: String,
    val widthPx: Int,
    val heightPx: Int
)

private fun AccessibilityNodeInfo.findFirstEditable(): AccessibilityNodeInfo? {
    if (isEditable) return this
    for (i in 0 until childCount) {
        getChild(i)?.findFirstEditable()?.let { return it }
    }
    return null
}

private fun AccessibilityNodeInfo.findFirstScrollable(): AccessibilityNodeInfo? {
    if (isScrollable) return this
    for (i in 0 until childCount) {
        getChild(i)?.findFirstScrollable()?.let { return it }
    }
    return null
}

private fun AccessibilityNodeInfo.clickableSelfOrParent(): AccessibilityNodeInfo? {
    if (isClickable && isEnabled) return this
    var current = parent
    var depth = 0
    while (current != null && depth < 6) {
        if (current.isClickable && current.isEnabled) {
            return current
        }
        current = current.parent
        depth += 1
    }
    return null
}
