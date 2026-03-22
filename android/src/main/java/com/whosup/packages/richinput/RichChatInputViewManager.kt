package com.whosup.packages.richinput

import android.graphics.Color
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

  @ReactProp(name = "color")
  override fun setColor(view: RichChatInputView?, color: Int?) {
    view?.setBackgroundColor(color ?: Color.TRANSPARENT)
  }

  companion object {
    const val NAME = "RichChatInputView"
  }
}
