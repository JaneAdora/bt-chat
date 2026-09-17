package dev.jane.btchat.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LinksTest {
    private fun found(text: String) = Links.find(text).map { text.substring(it.first, it.last + 1) }

    @Test fun findsHttpAndWwwLinks() {
        assertEquals(listOf("https://example.com/a?b=1", "www.kid.fun"), found("see https://example.com/a?b=1 and www.kid.fun ok"))
    }

    @Test fun dropsTrailingPunctuation() {
        assertEquals(listOf("https://example.com"), found("go to https://example.com."))
        assertEquals(listOf("https://example.com/x"), found("(https://example.com/x), right?"))
    }

    @Test fun plainTextHasNoLinks() {
        assertEquals(emptyList<String>(), found("where are you"))
    }

    @Test fun wwwGetsAScheme() {
        assertEquals("https://www.kid.fun", Links.href("www.kid.fun"))
        assertEquals("http://plain.test", Links.href("http://plain.test"))
    }
}
