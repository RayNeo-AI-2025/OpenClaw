package com.openclaw.app

import android.graphics.Color
import android.os.Bundle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openclaw.app.databinding.ActivityLanguagePickerBinding
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.toast.FToast
import kotlinx.coroutines.launch

/**
 * First-launch language picker.
 *
 * Shown once when [AppSettings.uiLanguage] is empty (i.e. user has never
 * explicitly chosen a language). Both options display in their native script
 * plus a subtitle in the other language, so speakers of either language can
 * understand what to select.
 *
 * Gesture map:
 *  SlideForward / SlideBackward / SlideUpwards / SlideDownwards
 *      → toggle between 中文 and English
 *  Click → confirm selection and return to AgentChatActivity
 */
class LanguagePickerActivity : BaseMirrorActivity<ActivityLanguagePickerBinding>() {

    /** 0 = 中文, 1 = English */
    private var selectedIndex = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Pre-select based on system locale detection
        selectedIndex = if (UiLanguage.fromCode(java.util.Locale.getDefault().language) == UiLanguage.ZH) 0 else 1
        renderPicker()
        collectTempleActions()
    }

    // Prevent back-press from skipping language selection
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Do nothing — user must choose a language
    }

    // ── Rendering ────────────────────────────────────────────────────────────

    private fun renderPicker() {
        val zhSelected = selectedIndex == 0
        mBindingPair.updateView {
            // Row 0: 中文
            rowChinese.setBackgroundColor(
                if (zhSelected) COLOR_ROW_SELECTED else Color.TRANSPARENT
            )
            tvChinese.setTextColor(if (zhSelected) COLOR_ACTIVE else COLOR_IDLE)
            tvChineseSub.setTextColor(if (zhSelected) COLOR_SUB_ACTIVE else COLOR_SUB_IDLE)

            // Row 1: English
            rowEnglish.setBackgroundColor(
                if (!zhSelected) COLOR_ROW_SELECTED else Color.TRANSPARENT
            )
            tvEnglish.setTextColor(if (!zhSelected) COLOR_ACTIVE else COLOR_IDLE)
            tvEnglishSub.setTextColor(if (!zhSelected) COLOR_SUB_ACTIVE else COLOR_SUB_IDLE)
        }
    }

    // ── Gesture handling ─────────────────────────────────────────────────────

    private fun collectTempleActions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.SlideForward,
                        is TempleAction.SlideBackward,
                        is TempleAction.SlideUpwards,
                        is TempleAction.SlideDownwards -> toggleSelection()
                        is TempleAction.Click          -> confirmAndExit()
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun toggleSelection() {
        selectedIndex = 1 - selectedIndex
        val label = if (selectedIndex == 0) "中文" else "English"
        FToast.show(label)
        renderPicker()
    }

    private fun confirmAndExit() {
        val chosen = if (selectedIndex == 0) UiLanguage.ZH else UiLanguage.EN
        AppSettings.uiLanguage = chosen.code
        // First launch: also align ASR language with the UI choice so that
        // speech recognition defaults to the language the user actually speaks.
        AppSettings.language = chosen.code
        UiStrings.switchTo(chosen)
        FToast.show(chosen.displayName)
        finish()
    }

    companion object {
        private val COLOR_ROW_SELECTED = Color.parseColor("#1500C896")
        private val COLOR_ACTIVE       = Color.parseColor("#00C896")
        private val COLOR_IDLE         = Color.parseColor("#484838")
        private val COLOR_SUB_ACTIVE   = Color.parseColor("#808060")
        private val COLOR_SUB_IDLE     = Color.parseColor("#303028")
    }
}
