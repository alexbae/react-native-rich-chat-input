package com.whosup.packages.richinput

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactContext
import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.events.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class RichChatInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    // Re-created on every reattach (see onAttachedToWindow). Detach cancels
    // the scope; if the view is later reattached (RN Navigation pop+push,
    // react-native-screens stash, FlatList recycling), `coroutineScope.launch`
    // on the cancelled scope would silently no-op — image-paste cache writes
    // and error dispatches would be lost.
    //
    // The field is mutated only from main-thread View lifecycle callbacks
    // (onAttachedToWindow / onDetachedFromWindow), but it is READ from
    // Dispatchers.IO (writeBytesToCache's error path re-launches on the
    // scope). @Volatile guarantees the IO thread sees the latest assignment
    // without a happens-before barrier from a synchronized block.
    @Volatile
    private var coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentMimeTypes: Array<String> = DEFAULT_MIME_TYPES.copyOf()

    init {
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT
        registerReceiveContentListener()
        setupTextWatcher()
        cleanStaleCacheFiles()
    }

    fun updateAcceptedMimeTypes(mimeTypes: ReadableArray?) {
        if (mimeTypes == null) return
        val sanitizedTypes = mutableListOf<String>()
        for (index in 0 until mimeTypes.size()) {
            val type = mimeTypes.getString(index)?.trim()
            if (!type.isNullOrEmpty()) {
                sanitizedTypes.add(type)
            }
        }

        val nextMimeTypes = if (sanitizedTypes.isEmpty()) {
            DEFAULT_MIME_TYPES
        } else {
            sanitizedTypes.distinct().toTypedArray()
        }

        if (!nextMimeTypes.contentEquals(currentMimeTypes)) {
            currentMimeTypes = nextMimeTypes.copyOf()
            registerReceiveContentListener()
            // Force the IME to recreate its InputConnection so the updated MIME
            // type list flows through onCreateInputConnection → EditorInfo. Without
            // this, the keyboard keeps using the previously advertised set and
            // image inserts (GIF/sticker) silently fall back to no-op until the
            // user blurs and refocuses the field.
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
                ?.restartInput(this)
        }
    }

    fun updateEditable(editable: Boolean) {
        isFocusable = editable
        isFocusableInTouchMode = editable
        isEnabled = editable
    }

    fun updateMultiline(multiline: Boolean) {
        if (multiline) {
            setSingleLine(false)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        } else {
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
        }
    }

    fun updateMaxLength(maxLength: Int) {
        filters = if (maxLength > 0) {
            arrayOf(InputFilter.LengthFilter(maxLength))
        } else {
            emptyArray()
        }
    }

    fun updateFontSize(size: Float) {
        val sp = if (size > 0f) size else 17f
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp)
    }

    private fun registerReceiveContentListener() {
        ViewCompat.setOnReceiveContentListener(
            this,
            currentMimeTypes,
            OnReceiveContentListener { _, payload ->
                val split = payload.partition { it.uri != null }
                val uriContent = split.first
                val remaining = split.second

                if (uriContent != null) {
                    val clip = uriContent.clip
                    for (i in 0 until clip.itemCount) {
                        clip.getItemAt(i).uri?.let { readAndDispatchContent(it) }
                    }
                }

                remaining
            }
        )
    }

    /**
     * Advertises the accepted rich-content MIME types on the InputConnection so
     * IMEs (Gboard, Samsung Keyboard, etc.) enable the image/GIF/sticker insert
     * affordance and route commitContent() calls back to our
     * OnReceiveContentListener. Without this, ViewCompat.setOnReceiveContentListener
     * silently no-ops for IME-driven inserts on API < 31, and even on 31+ some
     * IMEs continue to use the legacy commitContent path that requires the
     * EditorInfo advertisement.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs) ?: return null
        EditorInfoCompat.setContentMimeTypes(outAttrs, currentMimeTypes)
        return InputConnectionCompat.createWrapper(this, ic, outAttrs)
    }

    /**
     * Clears the input in a way that also resets the IME's composition state.
     *
     * Why this is more than `editableText.clear()`:
     *   - Android IMEs mark the in-progress composition by attaching
     *     Spannable.SPAN_COMPOSING flags onto the Editable. `editable.clear()`
     *     drops the characters but leaves the spans dangling at indices that
     *     no longer exist. The IME then keeps treating subsequent keystrokes
     *     as a continuation of the stale composition — characters get merged
     *     into / absorbed by the phantom composing region, producing the
     *     "incomplete first send / merged next send" bug that persists across
     *     every send until the app is restarted (because the EditText
     *     instance — and its corrupted span state — survives focus toggles).
     *   - InputMethodManager.restartInput tells the keyboard daemon to drop
     *     the InputConnection and recreate it, which also discards any
     *     pending composition events in flight from the IME process. Samsung
     *     keyboard's swipe / predict modes in particular won't recover
     *     without this.
     */
    fun clearTextSafely() {
        val editable = editableText ?: return
        BaseInputConnection.removeComposingSpans(editable)
        editable.clear()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.restartInput(this)

        // If the IME still re-injects characters on the next loop tick, the
        // bug above is firing — surface it via onError so production
        // occurrences are observable in Sentry. Uses the same code as the
        // iOS counterpart so the issue groups cross-platform.
        post {
            val current = editableText?.toString().orEmpty()
            if (current.isNotEmpty()) {
                val sample = if (current.length > 32) current.substring(0, 32) else current
                dispatchErrorEvent(
                    code = "IME_STALE_TEXT_FLUSHED",
                    message = "IME re-injected ${current.length} char(s) after clear() — " +
                        "subsequent sends may include leftover composing text " +
                        "(sample: \"$sample\")",
                )
            }
        }
    }

    /**
     * Reads the content URI bytes while the temporary permission granted by
     * OnReceiveContentListener is still valid, then writes to cache on a
     * background thread.
     *
     * The read itself happens on the main thread because the keyboard's
     * temporary URI grant expires when this method returns. To minimise the
     * UI-blocking window for oversized content, we:
     *
     *   1. Ask the provider for a declared size via openAssetFileDescriptor
     *      and bail out before reading a single byte if it already exceeds
     *      the 20 MB cap (many providers don't implement this and return
     *      UNKNOWN_LENGTH — fine, we fall through).
     *   2. Stream the bytes in chunks and abort as soon as the cumulative
     *      total crosses the cap, so a 50 MB paste no longer reads 50 MB.
     */
    private fun readAndDispatchContent(contentUri: Uri) {
        val contentResolver = context.contentResolver

        val mimeType = contentResolver.getType(contentUri)
        if (mimeType == null) {
            Log.e(TAG, "Cannot resolve MIME type for URI: $contentUri")
            dispatchErrorEvent(
                code = "RICH_CONTENT_MIME_UNKNOWN",
                message = "ContentResolver could not resolve a MIME type for the pasted URI: $contentUri",
            )
            return
        }

        // Pre-check size: many providers (keyboards included) expose the
        // payload length via AssetFileDescriptor. When they do, we can reject
        // oversized content without reading any bytes.
        //
        // The catch list is intentionally narrow — the three exception types
        // here cover "provider doesn't implement AFD" / "URI doesn't resolve" /
        // "permission missing" / "unsupported scheme". Any other exception
        // (NullPointerException, IllegalStateException, ...) indicates a
        // genuine bug we want to surface as a crash rather than silently
        // skip the pre-check.
        try {
            contentResolver.openAssetFileDescriptor(contentUri, "r")?.use { afd ->
                val declared = afd.length
                if (declared != AssetFileDescriptor.UNKNOWN_LENGTH &&
                    declared > MAX_FILE_SIZE_BYTES) {
                    Log.w(TAG, "Content declared size $declared exceeds limit, ignoring")
                    dispatchErrorEvent(
                        code = "RICH_CONTENT_TOO_LARGE",
                        message = "Pasted content declared size $declared bytes exceeds " +
                            "the ${MAX_FILE_SIZE_BYTES / 1024 / 1024} MB limit",
                    )
                    return
                }
            }
        } catch (e: java.io.IOException) {
            Log.d(TAG, "AFD pre-check skipped (I/O): ${e.message}")
        } catch (e: SecurityException) {
            Log.d(TAG, "AFD pre-check skipped (security): ${e.message}")
        } catch (e: IllegalArgumentException) {
            Log.d(TAG, "AFD pre-check skipped (illegal arg): ${e.message}")
        }

        val bytes: ByteArray
        var sizeOverflowed = false
        try {
            val inputStream = contentResolver.openInputStream(contentUri)
            if (inputStream == null) {
                Log.e(TAG, "openInputStream returned null for URI: $contentUri")
                dispatchErrorEvent(
                    code = "RICH_CONTENT_OPEN_FAILED",
                    message = "ContentResolver.openInputStream returned null for $contentUri",
                )
                return
            }
            bytes = inputStream.use { stream ->
                val out = ByteArrayOutputStream()
                val buf = ByteArray(STREAM_CHUNK_BYTES)
                var total = 0L
                var n = stream.read(buf)
                while (n >= 0) {
                    total += n
                    if (total > MAX_FILE_SIZE_BYTES) {
                        sizeOverflowed = true
                        break
                    }
                    out.write(buf, 0, n)
                    n = stream.read(buf)
                }
                out.toByteArray()
            }
        } catch (e: java.io.FileNotFoundException) {
            Log.e(TAG, "Content URI not found: $contentUri", e)
            dispatchErrorEvent(
                code = "RICH_CONTENT_FILE_NOT_FOUND",
                message = "Pasted content URI was not found: $contentUri",
                throwable = e,
            )
            return
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for URI: $contentUri", e)
            dispatchErrorEvent(
                code = "RICH_CONTENT_PERMISSION_DENIED",
                message = "Temporary read permission missing for pasted URI: $contentUri",
                throwable = e,
            )
            return
        } catch (e: java.io.IOException) {
            Log.e(TAG, "I/O error reading content URI: $contentUri", e)
            dispatchErrorEvent(
                code = "RICH_CONTENT_IO_ERROR",
                message = "I/O error while reading pasted URI: $contentUri",
                throwable = e,
            )
            return
        }

        if (sizeOverflowed) {
            Log.w(TAG, "Content exceeded ${MAX_FILE_SIZE_BYTES / 1024 / 1024} MB cap mid-stream, aborted")
            dispatchErrorEvent(
                code = "RICH_CONTENT_TOO_LARGE",
                message = "Pasted content exceeded the " +
                    "${MAX_FILE_SIZE_BYTES / 1024 / 1024} MB limit while streaming",
            )
            return
        }

        coroutineScope.launch {
            val fileUri = withContext(Dispatchers.IO) {
                writeBytesToCache(bytes, mimeType)
            }
            if (fileUri != null) {
                dispatchRichContentEvent(fileUri, mimeType)
            }
        }
    }

    private fun writeBytesToCache(bytes: ByteArray, mimeType: String): String? {
        return try {
            val extension = mimeTypeToExtension(mimeType)
            val fileName = "rich_content_${UUID.randomUUID()}.$extension"
            val cacheFile = File(context.cacheDir, fileName)
            FileOutputStream(cacheFile).use { it.write(bytes) }
            Uri.fromFile(cacheFile).toString()
        } catch (e: java.io.IOException) {
            Log.e(TAG, "Failed to write content to cache", e)
            // Dispatch on the main thread; this coroutine runs on Dispatchers.IO.
            coroutineScope.launch {
                dispatchErrorEvent(
                    code = "RICH_CONTENT_CACHE_WRITE_FAILED",
                    message = "Failed to persist pasted content to cache directory",
                    throwable = e,
                )
            }
            null
        }
    }

    private fun cleanStaleCacheFiles() {
        coroutineScope.launch {
            withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                context.cacheDir
                    .listFiles { file ->
                        file.name.startsWith("rich_content_") &&
                            (now - file.lastModified()) > CACHE_MAX_AGE_MS
                    }
                    ?.forEach { file ->
                        if (!file.delete()) {
                            Log.w(TAG, "Failed to delete stale cache file: ${file.name}")
                        }
                    }
            }
        }
    }

    private fun mimeTypeToExtension(mimeType: String): String = when {
        "gif" in mimeType -> "gif"
        "webp" in mimeType -> "webp"
        "png" in mimeType -> "png"
        "jpeg" in mimeType || "jpg" in mimeType -> "jpg"
        "svg" in mimeType -> "svg"
        "mp4" in mimeType -> "mp4"
        "webm" in mimeType -> "webm"
        "3gp" in mimeType -> "3gp"
        "mkv" in mimeType -> "mkv"
        else -> "bin"
    }

    private fun setupTextWatcher() {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                dispatchChangeTextEvent(s?.toString() ?: "")
                // Defer the size dispatch to the next message-loop tick.
                // afterTextChanged fires while the layout's recompute is still
                // in flight, so layout.height can be one frame stale on
                // multi-line transitions (Enter at end of long line that
                // re-wraps). Posting matches the iOS dispatch_async pattern.
                post { dispatchInputSizeChangeEvent() }
            }
        })
    }

    private fun dispatchRichContentEvent(uri: String, mimeType: String) {
        val params = Arguments.createMap().apply {
            putString("uri", uri)
            putString("mimeType", mimeType)
        }
        dispatchNativeEvent("topRichContent", params)
    }

    private fun dispatchChangeTextEvent(text: String) {
        val params = Arguments.createMap().apply {
            putString("text", text)
        }
        dispatchNativeEvent("topChangeText", params)
    }

    /**
     * Bridge a recoverable native error to JS so the host app can forward it
     * to its error tracker (e.g. Sentry.captureException). All slots are
     * strings — empty when N/A — to match the Fabric codegen event shape,
     * which does not support optional struct fields.
     */
    private fun dispatchErrorEvent(
        code: String,
        message: String,
        throwable: Throwable? = null,
    ) {
        val params = Arguments.createMap().apply {
            putString("code", code)
            putString("message", message)
            putString("nativeClass", throwable?.javaClass?.name ?: "")
            putString("nativeMessage", throwable?.localizedMessage ?: "")
            putString("nativeStack", throwable?.let { Log.getStackTraceString(it) } ?: "")
        }
        dispatchNativeEvent("topError", params)
    }

    private fun dispatchInputSizeChangeEvent(reportMissingLayout: Boolean = false) {
        val textLayout = layout
        if (textLayout == null) {
            if (reportMissingLayout && width > 0) {
                dispatchErrorEvent(
                    code = "INPUT_SIZE_DISPATCH_FAILED",
                    message = "EditText.getLayout() returned null after a width change " +
                        "(w=$width) — auto-grow height cannot be remeasured.",
                )
            }
            return
        }
        val density = resources.displayMetrics.density
        // textLayout.height is the height of the text content in pixels.
        // compoundPaddingTop/Bottom includes any internal padding (0 for this view).
        val contentHeightPx = textLayout.height + compoundPaddingTop + compoundPaddingBottom
        val params = Arguments.createMap().apply {
            putDouble("width", (width / density).toDouble())
            putDouble("height", (contentHeightPx / density).toDouble())
        }
        dispatchNativeEvent("topInputSizeChange", params)
    }

    /**
     * Width changes from device folding/unfolding, rotation, or multi-window
     * resizing re-wrap the text but do NOT fire afterTextChanged, so the JS-side
     * cached height keeps the previous wrap-width's value — the input renders
     * larger (or smaller) than the content actually needs. Re-dispatch so JS
     * can resync. Posted to the next loop tick so getLayout() reflects the
     * new width (the StaticLayout rebuild happens during measure, which on
     * some configuration-change paths runs after onSizeChanged).
     */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w != oldw && w > 0) {
            post { dispatchInputSizeChangeEvent(reportMissingLayout = true) }
        }
    }

    private inner class NativeEvent(
        surfaceId: Int,
        viewId: Int,
        private val name: String,
        private val data: WritableMap,
    ) : Event<NativeEvent>(surfaceId, viewId) {
        override fun getEventName(): String = name
        override fun getEventData(): WritableMap = data
    }

    private fun dispatchNativeEvent(eventName: String, params: WritableMap) {
        if (id == View.NO_ID) return
        val reactContext = context as? ReactContext ?: return
        val surfaceId = UIManagerHelper.getSurfaceId(this)
        val eventDispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
        eventDispatcher?.dispatchEvent(NativeEvent(surfaceId, id, eventName, params))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // If we're re-attaching after a previous detach, the scope has been
        // cancelled — recreate it so future coroutine launches actually run.
        // First-attach (immediately after construction) finds the scope alive
        // and this is a no-op.
        if (!coroutineScope.isActive) {
            coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        coroutineScope.cancel()
    }

    private companion object {
        val DEFAULT_MIME_TYPES: Array<String> = arrayOf("image/*")
        const val MAX_FILE_SIZE_BYTES = 20L * 1024L * 1024L  // 20 MB
        const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L  // 7 days
        // Tuned so the 20 MB cap is reached in a small number of iterations
        // without blocking the main thread for too long per chunk.
        const val STREAM_CHUNK_BYTES = 64 * 1024
        const val TAG = "RichChatInput"
    }
}
