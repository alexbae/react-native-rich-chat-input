package com.whosup.packages.richinput

import android.content.Context
import android.net.Uri
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import androidx.appcompat.widget.AppCompatEditText
import androidx.core.view.ContentInfoCompat
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
    private var currentMimeTypes: Array<String> = arrayOf("image/*")

    init {
        background = null
        setPadding(0, 0, 0, 0)
        gravity = Gravity.TOP or Gravity.START
        inputType = InputType.TYPE_CLASS_TEXT
        registerReceiveContentListener()
        setupTextWatcher()
    }

    fun updateAcceptedMimeTypes(mimeTypes: ReadableArray?) {
        if (mimeTypes == null) return
        val types = Array(mimeTypes.size()) { mimeTypes.getString(it) }
        if (!types.contentEquals(currentMimeTypes)) {
            currentMimeTypes = types
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
            val mimeType = contentResolver.getType(contentUri) ?: return null
            val extension = mimeTypeToExtension(mimeType)
            val fileName = "rich_content_${UUID.randomUUID()}.$extension"
            val cacheFile = File(context.cacheDir, fileName)

            contentResolver.openInputStream(contentUri)?.use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            } ?: return null

            Pair(Uri.fromFile(cacheFile).toString(), mimeType)
        } catch (e: Exception) {
            null
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

    private fun dispatchNativeEvent(eventName: String, params: WritableMap) {
        if (id == View.NO_ID) return
        val reactContext = context as? ReactContext ?: return
        val surfaceId = UIManagerHelper.getSurfaceId(this)
        val eventDispatcher = UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)
        eventDispatcher?.dispatchEvent(
            object : Event<Event<*>>(surfaceId, id) {
                override fun getEventName(): String = eventName
                override fun getEventData(): WritableMap = params
            }
        )
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        coroutineScope.cancel()
    }
}
