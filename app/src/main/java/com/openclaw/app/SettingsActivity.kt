package com.openclaw.app

import android.graphics.Color
import android.os.Bundle
import androidx.annotation.ColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.openclaw.app.asr.AppLanguage
import com.openclaw.app.databinding.ActivitySettingsBinding
import com.ffalcon.mercury.android.sdk.touch.TempleAction
import com.ffalcon.mercury.android.sdk.ui.activity.BaseMirrorActivity
import com.ffalcon.mercury.android.sdk.ui.toast.FToast
import kotlinx.coroutines.launch

/**
 * In-glasses settings screen.
 *
 * Launched from [AgentChatActivity] via a TripleClick gesture.
 * Language and listen-mode changes are held in temporary state and only committed
 * to [AppSettings] when the user double-clicks (save) or selects the reset row.
 * Triple-click cancels without saving.
 *
 * Rows:
 *  0 — Listen Mode   (cycle: continuous / oneshot)
 *  1 — ASR Language   (cycle: 8 languages)
 *  2 — UI Language    (cycle: 中文 / English)
 *  3 — Reset Chat     (action: sets AppSettings.pendingReset, saves, exits)
 *
 * Gesture map:
 *  SlideUpwards   / SlideDownwards  → navigate between rows
 *  SlideForward   / SlideBackward   → cycle the selected row's value
 *  Click                            → cycle forward / confirm action row
 *  DoubleClick                      → save language + mode, exit
 *  TripleClick                      → cancel and exit (no save)
 *
 * NOTE: LongClick deliberately NOT used — it triggers the system settings panel.
 */
class SettingsActivity : BaseMirrorActivity<ActivitySettingsBinding>() {

    // ── Temporary editing state (not persisted until DoubleClick or reset) ────

    private val languages   = AppLanguage.entries
    private val uiLanguages = UiLanguage.entries

    private var selectedRow     = 0
    private var langIndex       = 0
    private var listenModeIndex = 0
    private var uiLangIndex     = 0

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCurrentSettings()
        renderSettings()
        collectTempleActions()
    }

    // ── Initialisation ─────────────────────────────────────────────────────────

    /** Populate temp state from whatever AppSettings currently holds. */
    private fun loadCurrentSettings() {
        langIndex = languages.indexOfFirst { it.code == AppSettings.language }
            .coerceAtLeast(0)
        listenModeIndex = if (AppSettings.listenModeKey == "oneshot") 1 else 0
        uiLangIndex = uiLanguages.indexOfFirst { it.code == AppSettings.uiLanguage }
            .coerceAtLeast(0)
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    private fun listenModeDisplay(index: Int): String =
        if (index == 0) S.continuous else S.oneshot

    private fun renderSettings() {
        val listenLabel   = listenModeDisplay(listenModeIndex)
        val languageLabel = languages[langIndex].displayName
        val uiLangLabel   = uiLanguages[uiLangIndex].displayName

        mBindingPair.updateView {
            // ── Title & hint bar ─────────────────────────────────────────────
            tvSettingsTitle.text = S.settingsTitle
            tvSettingsHint.text  = S.settingsHintBar

            // ── Row 0: Listen Mode ──────────────────────────────────────────
            val listenSel = selectedRow == ROW_LISTEN_MODE
            rowListenMode.setBackgroundColor(
                if (listenSel) COLOR_ROW_SELECTED else Color.TRANSPARENT
            )
            tvListenModeLabel.text = S.labelListenMode
            tvListenModeLabel.setTextColor(
                if (listenSel) COLOR_LABEL_ACTIVE else COLOR_LABEL_IDLE
            )
            tvListenModeValue.text = if (listenSel) "← $listenLabel →" else listenLabel
            tvListenModeValue.setTextColor(
                if (listenSel) COLOR_VALUE_ACTIVE else COLOR_VALUE_IDLE
            )

            // ── Row 1: ASR Language ─────────────────────────────────────────
            val langSel = selectedRow == ROW_LANGUAGE
            rowLanguage.setBackgroundColor(
                if (langSel) COLOR_ROW_SELECTED else Color.TRANSPARENT
            )
            tvLanguageLabel.text = S.labelAsrLanguage
            tvLanguageLabel.setTextColor(
                if (langSel) COLOR_LABEL_ACTIVE else COLOR_LABEL_IDLE
            )
            tvLanguageValue.text = if (langSel) "← $languageLabel →" else languageLabel
            tvLanguageValue.setTextColor(
                if (langSel) COLOR_VALUE_ACTIVE else COLOR_VALUE_IDLE
            )

            // ── Row 2: UI Language ──────────────────────────────────────────
            val uiSel = selectedRow == ROW_UI_LANGUAGE
            rowUiLanguage.setBackgroundColor(
                if (uiSel) COLOR_ROW_SELECTED else Color.TRANSPARENT
            )
            tvUiLanguageLabel.text = S.labelUiLanguage
            tvUiLanguageLabel.setTextColor(
                if (uiSel) COLOR_LABEL_ACTIVE else COLOR_LABEL_IDLE
            )
            tvUiLanguageValue.text = if (uiSel) "← $uiLangLabel →" else uiLangLabel
            tvUiLanguageValue.setTextColor(
                if (uiSel) COLOR_VALUE_ACTIVE else COLOR_VALUE_IDLE
            )

            // ── Row 3: Reset (action, no cycle arrows) ──────────────────────
            val resetSel = selectedRow == ROW_RESET
            rowReset.setBackgroundColor(
                if (resetSel) COLOR_RESET_ROW_SELECTED else Color.TRANSPARENT
            )
            tvResetLabel.text = S.labelReset
            tvResetLabel.setTextColor(
                if (resetSel) COLOR_RESET_ACTIVE else COLOR_RESET_IDLE
            )
        }
    }

    // ── Gesture handling ───────────────────────────────────────────────────────

    private fun collectTempleActions() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                templeActionViewModel.state.collect { action ->
                    when (action) {
                        is TempleAction.SlideUpwards   -> navigateRow(-1)
                        is TempleAction.SlideDownwards -> navigateRow(+1)
                        is TempleAction.SlideForward   -> cycleOrConfirm(+1)
                        is TempleAction.SlideBackward  -> cycleOrConfirm(-1)
                        is TempleAction.Click          -> cycleOrConfirm(+1)
                        is TempleAction.DoubleClick    -> saveAndExit()
                        is TempleAction.TripleClick    -> cancelAndExit()
                        // LongClick intentionally absent — would trigger system settings panel
                        else -> Unit
                    }
                }
            }
        }
    }

    private fun navigateRow(delta: Int) {
        selectedRow = (selectedRow + delta + ROW_COUNT) % ROW_COUNT
        renderSettings()
    }

    private fun cycleOrConfirm(delta: Int) {
        when (selectedRow) {
            ROW_LISTEN_MODE -> {
                listenModeIndex = (listenModeIndex + delta + 2) % 2
                FToast.show(listenModeDisplay(listenModeIndex))
                renderSettings()
            }
            ROW_LANGUAGE -> {
                langIndex = (langIndex + delta + languages.size) % languages.size
                FToast.show(languages[langIndex].displayName)
                renderSettings()
            }
            ROW_UI_LANGUAGE -> {
                uiLangIndex = (uiLangIndex + delta + uiLanguages.size) % uiLanguages.size
                // Live-preview: switch UI language immediately so labels update
                UiStrings.switchTo(uiLanguages[uiLangIndex])
                FToast.show(uiLanguages[uiLangIndex].displayName)
                renderSettings()
            }
            ROW_RESET -> {
                // Immediate action: save current settings and request a conversation reset
                AppSettings.pendingReset = true
                saveAndExit()
            }
        }
    }

    private fun saveAndExit() {
        AppSettings.language      = languages[langIndex].code
        AppSettings.listenModeKey = if (listenModeIndex == 0) "continuous" else "oneshot"
        AppSettings.uiLanguage    = uiLanguages[uiLangIndex].code
        UiStrings.switchTo(uiLanguages[uiLangIndex])
        if (AppSettings.pendingReset) {
            FToast.show(S.savedAndReset)
        } else {
            FToast.show(S.settingsSaved)
        }
        finish()
    }

    private fun cancelAndExit() {
        // Revert live-preview of UI language
        UiStrings.switchTo(UiLanguage.fromCode(AppSettings.uiLanguage))
        FToast.show(S.cancelled)
        finish()
    }

    // ── Constants ──────────────────────────────────────────────────────────────

    companion object {
        private const val ROW_COUNT       = 4
        private const val ROW_LISTEN_MODE = 0
        private const val ROW_LANGUAGE    = 1
        private const val ROW_UI_LANGUAGE = 2
        private const val ROW_RESET       = 3

        @ColorInt private val COLOR_ROW_SELECTED       = Color.parseColor("#1500C896")
        @ColorInt private val COLOR_LABEL_ACTIVE       = Color.parseColor("#00C896")
        @ColorInt private val COLOR_LABEL_IDLE         = Color.parseColor("#484838")
        @ColorInt private val COLOR_VALUE_ACTIVE       = Color.parseColor("#EEE8DC")
        @ColorInt private val COLOR_VALUE_IDLE         = Color.parseColor("#484838")

        // Reset row uses orange/warning palette
        @ColorInt private val COLOR_RESET_ROW_SELECTED = Color.parseColor("#15FF6B35")
        @ColorInt private val COLOR_RESET_ACTIVE       = Color.parseColor("#FF6B35")
        @ColorInt private val COLOR_RESET_IDLE         = Color.parseColor("#4A3020")
    }
}
