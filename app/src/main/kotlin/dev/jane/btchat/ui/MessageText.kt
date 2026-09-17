package dev.jane.btchat.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.style.TextDecoration

/** Finds http(s) and www links in plain text. Trailing punctuation is left out of the link. */
object Links {
    private val pattern = Regex("""(?i)\b(?:https?://|www\.)[^\s<>"']+""")
    private val trailing = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}', '\'', '"')

    fun find(text: String): List<IntRange> = pattern.findAll(text).map { m ->
        var end = m.range.last
        while (end > m.range.first && text[end] in trailing) end--
        m.range.first..end
    }.toList()

    fun href(raw: String): String = if (raw.startsWith("www.", ignoreCase = true)) "https://$raw" else raw
}

/** Plain text with links turned into tappable, underlined URL annotations. */
fun linkify(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    var cursor = 0
    val styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    for (range in Links.find(text)) {
        append(text.substring(cursor, range.first))
        val raw = text.substring(range.first, range.last + 1)
        withLink(LinkAnnotation.Url(Links.href(raw), styles)) { append(raw) }
        cursor = range.last + 1
    }
    append(text.substring(cursor))
}
