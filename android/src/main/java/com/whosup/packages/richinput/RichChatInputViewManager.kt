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

  @ReactProp(name = "acceptedMimeTypes")
  override fun setAcceptedMimeTypes(view: RichChatInputView?, value: ReadableArray?) {
    view?.updateAcceptedMimeTypes(value)
  }

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
        )
    )
  }

  companion object {
    const val NAME = "RichChatInputView"
  }
}
