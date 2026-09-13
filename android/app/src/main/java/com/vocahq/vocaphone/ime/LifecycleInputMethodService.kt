package com.vocahq.vocaphone.ime

import android.content.res.Configuration
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.inputmethodservice.InputMethodService.Insets
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.graphics.drawable.toDrawable
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/** Supplies the owners Compose normally receives from an Activity. */
abstract class LifecycleInputMethodService : InputMethodService(),
    LifecycleOwner,
    ViewModelStoreOwner,
    SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val store = ViewModelStore()
    private val savedStateController = SavedStateRegistryController.create(this)
    private var inputComposeView: ComposeView? = null
    /** Prior soft-input attrs so leave/non-shortcut can undo WRAP_CONTENT+BOTTOM. */
    private var softInputLayoutWrapped = false
    private var savedSoftInputHeight: Int? = null
    private var savedSoftInputGravity: Int? = null
    private var savedComposeHeight: Int? = null
    private var savedComposeGravity: Int? = null

    final override val lifecycle: Lifecycle get() = lifecycleRegistry
    final override val viewModelStore: ViewModelStore get() = store
    final override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
    }

    final override fun onCreateInputView(): View {
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }

        val surfaceColor = keyboardSurfaceColor()
        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LifecycleInputMethodService)
            setViewTreeViewModelStoreOwner(this@LifecycleInputMethodService)
            setViewTreeSavedStateRegistryOwner(this@LifecycleInputMethodService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            // Match keyboard chrome so nav-bar padding is not a transparent hole.
            setBackgroundColor(surfaceColor)
            // Pad before the first measure so the IME window height includes it.
            setPadding(0, 0, 0, navigationBarBottomInsetPx(this))
            setContent { KeyboardContent() }
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bottom = navigationBarBottomInsetPx(view, insets)
                if (view.paddingBottom != bottom) {
                    view.setPadding(0, 0, 0, bottom)
                }
                insets
            }
        }
        inputComposeView = composeView

        window?.window?.let { imeWindow ->
            // Edge-to-edge so navigation-bar insets are dispatched to the input view.
            WindowCompat.setDecorFitsSystemWindows(imeWindow, false)
            imeWindow.setBackgroundDrawable(surfaceColor.toDrawable())
            imeWindow.decorView.apply {
                setViewTreeLifecycleOwner(this@LifecycleInputMethodService)
                setViewTreeViewModelStoreOwner(this@LifecycleInputMethodService)
                setViewTreeSavedStateRegistryOwner(this@LifecycleInputMethodService)
            }
        }
        return composeView
    }

    @Composable
    protected abstract fun KeyboardContent()

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        requestInputViewRemeasure()
    }

    /**
     * HeliBoard leaves a tall IME window. WRAP_CONTENT + BOTTOM sits the
     * short bar at the bottom of that leftover window instead of the top.
     * Leaving the shortcut restores the prior height/gravity so the typing
     * keyboard is not stuck in wrap forever.
     */
    protected fun applySoftInputWindowLayout(wrapToContentAtBottom: Boolean) {
        if (wrapToContentAtBottom) {
            captureSoftInputLayoutIfNeeded()
            window?.window?.let { imeWindow ->
                val attrs = imeWindow.attributes
                attrs.gravity = (attrs.gravity and Gravity.HORIZONTAL_GRAVITY_MASK) or
                    VoiceShortcutIme.SHORTCUT_WINDOW_GRAVITY
                attrs.height = VoiceShortcutIme.SHORTCUT_WINDOW_HEIGHT
                imeWindow.attributes = attrs
            }
            val composeParams = inputComposeView?.layoutParams
            if (composeParams is FrameLayout.LayoutParams) {
                composeParams.gravity = Gravity.BOTTOM
                composeParams.height = WindowManager.LayoutParams.WRAP_CONTENT
                inputComposeView?.layoutParams = composeParams
            }
        } else {
            restoreSoftInputLayoutIfNeeded()
        }
        requestInputViewRemeasure()
    }

    private fun captureSoftInputLayoutIfNeeded() {
        if (softInputLayoutWrapped) return
        window?.window?.let { imeWindow ->
            val attrs = imeWindow.attributes
            savedSoftInputHeight = attrs.height
            savedSoftInputGravity = attrs.gravity
        }
        val composeParams = inputComposeView?.layoutParams
        if (composeParams is FrameLayout.LayoutParams) {
            savedComposeHeight = composeParams.height
            savedComposeGravity = composeParams.gravity
        }
        softInputLayoutWrapped = true
    }

    private fun restoreSoftInputLayoutIfNeeded() {
        if (!softInputLayoutWrapped) return
        window?.window?.let { imeWindow ->
            val attrs = imeWindow.attributes
            attrs.height = VoiceShortcutIme.restoredSoftInputHeightPx(savedSoftInputHeight)
            val priorGravity = savedSoftInputGravity
            if (priorGravity != null) {
                attrs.gravity = priorGravity
            } else {
                attrs.gravity = attrs.gravity and Gravity.HORIZONTAL_GRAVITY_MASK
            }
            imeWindow.attributes = attrs
        }
        val composeParams = inputComposeView?.layoutParams
        if (composeParams is FrameLayout.LayoutParams) {
            composeParams.height = savedComposeHeight ?: WindowManager.LayoutParams.MATCH_PARENT
            composeParams.gravity = savedComposeGravity ?: Gravity.NO_GRAVITY
            inputComposeView?.layoutParams = composeParams
        }
        softInputLayoutWrapped = false
        savedSoftInputHeight = null
        savedSoftInputGravity = null
        savedComposeHeight = null
        savedComposeGravity = null
    }

    protected fun requestInputViewRemeasure() {
        inputComposeView?.requestLayout()
        window?.window?.decorView?.requestLayout()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!voiceShortcutWindowActive()) return
        val top = VoiceShortcutIme.contentTopInsetsPx(
            isVoiceShortcut = true,
            windowHeightPx = window?.window?.decorView?.height ?: 0,
            measuredInputHeightPx = inputComposeView?.height ?: 0,
            fallbackBarHeightPx = voiceShortcutBarHeightPx(),
            defaultContentTopInsetsPx = outInsets.contentTopInsets,
        )
        outInsets.contentTopInsets = top
        outInsets.visibleTopInsets = top
    }

    protected open fun voiceShortcutWindowActive(): Boolean = false

    /** Dictation-bar dp + listening-bar padding; override with the live setting. */
    protected open fun voiceShortcutBarHeightDp(): Int = VoiceShortcutIme.FALLBACK_BAR_DP

    protected open fun voiceShortcutBarHeightPx(): Int {
        val bar = (voiceShortcutBarHeightDp() * resources.displayMetrics.density).toInt()
        return bar + (inputComposeView?.paddingBottom ?: 0)
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        // Insets are often only available once the IME window is showing.
        inputComposeView?.let { view ->
            val bottom = navigationBarBottomInsetPx(view)
            if (view.paddingBottom != bottom) {
                view.setPadding(0, 0, 0, bottom)
                // First layout may have used a 0 inset; force the soft-input
                // window to remeasure now that padding is known.
                view.requestLayout()
                window?.window?.decorView?.requestLayout()
            }
            ViewCompat.requestApplyInsets(view)
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        super.onFinishInputView(finishingInput)
    }

    override fun onDestroy() {
        when (lifecycleRegistry.currentState) {
            Lifecycle.State.RESUMED -> lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            else -> Unit
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        inputComposeView = null
        softInputLayoutWrapped = false
        savedSoftInputHeight = null
        savedSoftInputGravity = null
        savedComposeHeight = null
        savedComposeGravity = null
        store.clear()
        super.onDestroy()
    }

    private fun keyboardSurfaceColor(): Int {
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        // Keep in sync with Theme.kt surfaceContainerLowest.
        return if (night) Color.rgb(0x10, 0x12, 0x10) else Color.WHITE
    }

    private fun navigationBarBottomInsetPx(
        view: View,
        insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(view),
    ): Int {
        if (insets != null) {
            val navigation = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val gestures = insets.getInsets(WindowInsetsCompat.Type.systemGestures()).bottom
            val tappable = insets.getInsets(WindowInsetsCompat.Type.tappableElement()).bottom
            val resolved = maxOf(navigation, gestures, tappable)
            if (resolved > 0) return resolved
        }

        // IME windows often report 0 insets even when gesture nav draws over the
        // bottom row. Fall back to the framework nav-bar height for gesture mode.
        val navigationMode = Settings.Secure.getInt(contentResolver, "navigation_mode", 0)
        if (navigationMode != GESTURE_NAVIGATION_MODE) return 0

        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val fromRes = if (resId > 0) resources.getDimensionPixelSize(resId) else 0
        val fallback = (GESTURE_NAV_FALLBACK_DP * resources.displayMetrics.density).toInt()
        return maxOf(fromRes, fallback)
    }

    private companion object {
        const val GESTURE_NAVIGATION_MODE = 2
        const val GESTURE_NAV_FALLBACK_DP = 48
    }
}
