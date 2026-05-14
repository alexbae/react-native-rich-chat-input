package com.whosup.packages.richinput

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.RichChatInputViewManagerInterface
import com.facebook.react.viewmanagers.RichChatInputViewManagerDelegate

@ReactModule(name = RichChatInputViewManager.NAME)
class RichChatInputViewManager : SimpleViewManager<RichChatInputView>(),
  RichChatInputViewManagerInterface<RichChatInputView> {
  private val mDelegate: ViewManagerDelegate<RichChatInputView>

  init {
    mDelegate = RichChatInputViewManagerDelegate(this)
  }

  override fun getDelegate(): ViewManagerDelegate<RichChatInputView>? {
    return mDelegate
  }

  override fun getName(): String {
    return NAME
  }

  public override fun createViewInstance(context: ThemedReactContext): RichChatInputView {
    return RichChatInputView(context)
  }

  @ReactProp(name = "placeholder")
  override fun setPlaceholder(view: RichChatInputView?, value: String?) {
    view?.hint = value
  }

  @ReactProp(name = "placeholderTextColor", customType = "Color")
  override fun setPlaceholderTextColor(view: RichChatInputView?, value: Int?) {
    if (value == null) {
      view?.setHintTextColor(view.currentTextColor)
      return
    }
    view?.setHintTextColor(value)
  }

  @ReactProp(name = "editable", defaultBoolean = true)
  override fun setEditable(view: RichChatInputView?, value: Boolean) {
    view?.updateEditable(value)
  }

  @ReactProp(name = "multiline", defaultBoolean = false)
  override fun setMultiline(view: RichChatInputView?, value: Boolean) {
    view?.updateMultiline(value)
  }

  @ReactProp(name = "maxLength", defaultInt = 0)
  override fun setMaxLength(view: RichChatInputView?, value: Int) {
    view?.updateMaxLength(value)
  }

  @ReactProp(name = "fontSize", defaultFloat = 0f)
  override fun setFontSize(view: RichChatInputView?, value: Float) {
    view?.updateFontSize(value)
  }

  @ReactProp(name = "acceptedMimeTypes")
  override fun setAcceptedMimeTypes(view: RichChatInputView?, value: ReadableArray?) {
    view?.updateAcceptedMimeTypes(value)
  }

  override fun clear(view: RichChatInputView?) {
    view?.clearTextSafely()
  }

  // Manually advertised bubbling event map.
  //
  // Fabric's Codegen generates equivalent registration through
  // RichChatInputViewManagerDelegate (constructed in `init` and exposed via
  // getDelegate()). On a clean New-Architecture setup that delegate is the
  // single source of truth, and this override is redundant.
  //
  // We keep the override anyway because some RN hot-reload paths re-create
  // ViewManagers without re-querying the Codegen delegate's event constants —
  // when that happens (notably on Metro full reloads during dev), events with
  // names not present in the legacy custom-bubbling map are silently dropped
  // until the app process restarts. Returning the same map manually makes the
  // legacy lookup path work too.
  //
  // CONSISTENCY RULE: When you add a new event prop in
  // src/RichChatInputViewNativeComponent.ts, ALSO append an entry below.
  // Drift here means JS handlers stop firing in dev hot-reload scenarios
  // even though prod builds (where the Codegen path is always used) work.
  override fun getExportedCustomBubblingEventTypeConstants(): MutableMap<String, Any> {
    return mutableMapOf(
      "topRichContent" to
        mutableMapOf(
          "phasedRegistrationNames" to
            mutableMapOf(
              "bubbled" to "onRichContent"
            )
        ),
      "topChangeText" to
        mutableMapOf(
          "phasedRegistrationNames" to
            mutableMapOf(
              "bubbled" to "onChangeText"
            )
        ),
      "topInputSizeChange" to
        mutableMapOf(
          "phasedRegistrationNames" to
            mutableMapOf(
              "bubbled" to "onInputSizeChange"
            )
        ),
      "topError" to
        mutableMapOf(
          "phasedRegistrationNames" to
            mutableMapOf(
              "bubbled" to "onError"
            )
        )
    )
  }

  companion object {
    const val NAME = "RichChatInputView"
  }
}
