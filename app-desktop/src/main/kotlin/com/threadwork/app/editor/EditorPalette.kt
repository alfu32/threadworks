package com.threadwork.app.editor

import com.threadwork.core.diagnostics.DiagnosticSeverity
import java.awt.Color

data class EditorPalette(
    val background: Color,
    val gutterBackground: Color,
    val border: Color,
    val separator: Color,
    val mutedText: Color,
    val defaultText: Color,
    val selection: Color,
    val caret: Color,
    val popupBackground: Color,
    val popupBorder: Color,
    val popupTitle: Color,
    val popupDetail: Color,
    val completionSelection: Color,
    val scrollbarTrack: Color,
    val scrollbarThumb: Color,
    val diagnosticError: Color,
    val diagnosticWarning: Color,
    val diagnosticInfo: Color,
    val syntax: SyntaxColorPalette,
) {
    fun diagnosticColor(severity: DiagnosticSeverity): Color = when (severity) {
        DiagnosticSeverity.Error -> diagnosticError
        DiagnosticSeverity.Warning -> diagnosticWarning
        DiagnosticSeverity.Info -> diagnosticInfo
    }

    companion object {
        fun dark() = EditorPalette(
            background = Color(0x1e1e1e),
            gutterBackground = Color(0x252526),
            border = Color(0x5f5f5f),
            separator = Color(0x3c3c3c),
            mutedText = Color(0x858585),
            defaultText = RegexSyntaxHighlighter.DarkPalette.default,
            selection = Color(0x3a5f8a),
            caret = Color(0xf2f2f2),
            popupBackground = Color(0x252526),
            popupBorder = Color(0x5f5f5f),
            popupTitle = Color(0x9cdcfe),
            popupDetail = Color(0x9cdcfe),
            completionSelection = Color(0x094771),
            scrollbarTrack = Color(0x3c3c3c),
            scrollbarThumb = Color(0x858585),
            diagnosticError = Color(0xff5555),
            diagnosticWarning = Color(0xd7ba7d),
            diagnosticInfo = Color(0x75beff),
            syntax = RegexSyntaxHighlighter.DarkPalette,
        )

        fun light() = EditorPalette(
            background = Color(0xffffff),
            gutterBackground = Color(0xf3f4f6),
            border = Color(0x8c959f),
            separator = Color(0xd0d7de),
            mutedText = Color(0x57606a),
            defaultText = RegexSyntaxHighlighter.LightPalette.default,
            selection = Color(0xb6d7ff),
            caret = Color(0x24292f),
            popupBackground = Color(0xffffff),
            popupBorder = Color(0x8c959f),
            popupTitle = Color(0x0550ae),
            popupDetail = Color(0x0550ae),
            completionSelection = Color(0xcce5ff),
            scrollbarTrack = Color(0xd8dee4),
            scrollbarThumb = Color(0x8c959f),
            diagnosticError = Color(0xcf222e),
            diagnosticWarning = Color(0x9a6700),
            diagnosticInfo = Color(0x0969da),
            syntax = RegexSyntaxHighlighter.LightPalette,
        )
    }
}
