package com.mewmix.glaive.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.googlefonts.GoogleFont
import androidx.compose.ui.text.googlefonts.Font
import com.mewmix.glaive.R

object GoogleFontsProvider {
    val provider = GoogleFont.Provider(
        providerAuthority = "com.google.android.gms.fonts",
        providerPackage = "com.google.android.gms",
        certificates = R.array.com_google_android_gms_fonts_certs
    )

    fun getFontFamily(fontName: String): FontFamily {
        if (fontName.isEmpty() || fontName == "Monospace") return FontFamily.Monospace
        if (fontName == "SansSerif") return FontFamily.SansSerif
        if (fontName == "Serif") return FontFamily.Serif
        if (fontName == "Cursive") return FontFamily.Cursive

        val font = GoogleFont(fontName)
        return FontFamily(
            Font(googleFont = font, fontProvider = provider)
        )
    }
}
