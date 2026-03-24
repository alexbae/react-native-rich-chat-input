package com.whosup.packages.richinput

import android.content.Context
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.View.MeasureSpec
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.OnReceiveContentListener
import androidx.core.view.ViewCompat
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

class RichChatInputView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : AppCompatEditText(context, attrs, defStyleAttr) {

    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentMimeTypes: Array<String> = DEFAULT_MIME_TYPES.copyOf()

    init {
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.TOP or Gravity.START
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
                        clip.getItemAt(i).uri?.let { copyAndDispatchContent(it) }
                    }
                }

                remaining
            }
        )
    }

    private fun copyAndDispatchContent(contentUri: Uri) {
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) {
                copyToCache(contentUri)
            }
            if (result != null) {
                dispatchRichContentEvent(result.first, result.second)
            }
        }
    }

    private fun copyToCache(contentUri: Uri): Pair<String, String>? {
        return try {
            val contentResolver = context.contentResolver
            val mimeType = contentResolver.getType(contentUri)
            if (mimeType == null) {
                Log.e(TAG, "Cannot resolve MIME type for URI: $contentUri")
                return null
            }
            val extension = mimeTypeToExtension(mimeType)
            val fileName = "rich_content_${UUID.randomUUID()}.$extension"
            val cacheFile = File(context.cacheDir, fileName)

            val inputStream = try {
                contentResolver.openInputStream(contentUri)
            } catch (e: java.io.FileNotFoundException) {
                Log.e(TAG, "Content URI not found (expired?): $contentUri", e)
                return null
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for URI: $contentUri", e)
                return null
            }

            if (inputStream == null) {
                Log.e(TAG, "openInputStream returned null for URI: $contentUri")
                return null
            }

            inputStream.use { input ->
                var totalBytes = 0L
                FileOutputStream(cacheFile).use { output ->
                    val buffer = ByteArray(8 * 1024)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        totalBytes += read
                        if (totalBytes > MAX_FILE_SIZE_BYTES) {
                            Log.w(TAG, "File exceeds ${MAX_FILE_SIZE_BYTES / 1024 / 1024} MB limit, aborting copy")
                            output.flush()
                            cacheFile.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                }
            }

            Pair(Uri.fromFile(cacheFile).toString(), mimeType)
        } catch (e: java.io.IOException) {
            Log.e(TAG, "I/O error copying content to cache", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error copying content to cache", e)
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
                dispatchInputSizeChangeEvent()
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

    private fun dispatchInputSizeChangeEvent() {
        val textLayout = layout ?: return
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

    /**
     * Guard against negative or zero width specs that Fabric can pass during initial layout.
     * AppCompatEditText trying to measure hint text with a negative width causes
     * android.text.Layout: -N < 0 crash in Fabric's TextLayoutManager.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val wSize = MeasureSpec.getSize(widthMeasureSpec)
        val safeWidthSpec = if (wSize < 0) {
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        } else {
            widthMeasureSpec
        }
        super.onMeasure(safeWidthSpec, heightMeasureSpec)
        // Ensure we never report 0 dimensions to Fabric — a zero-size view
        // can cause Yoga to allocate negative space to sibling Text nodes.
        setMeasuredDimension(maxOf(measuredWidth, 1), maxOf(measuredHeight, 1))
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        coroutineScope.cancel()
    }

    private companion object {
        val DEFAULT_MIME_TYPES: Array<String> = arrayOf("image/*")
        const val MAX_FILE_SIZE_BYTES = 20L * 1024L * 1024L  // 20 MB
        const val CACHE_MAX_AGE_MS = 7L * 24L * 60L * 60L * 1000L  // 7일
        const val TAG = "RichChatInput"
    }
}
