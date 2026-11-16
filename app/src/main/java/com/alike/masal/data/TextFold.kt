package com.alike.masal.data

import java.text.Normalizer
import java.util.Locale

private val Marks = Regex("\\p{M}")

/**
 * Lower case without accents, with the Turkish dotted and dotless I mapped to a plain "i",
 * so "cafe" finds "Café" and "istanbul" finds "İSTANBUL". İ is replaced before lowercasing,
 * because lowercasing it first would leave "i" plus a combining dot.
 */
fun foldText(s: String): String =
    Normalizer.normalize(s.replace("İ", "I").replace("ı", "i").lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Marks, "")
