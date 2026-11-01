package com.crsmthw.hingegate.ui

import com.crsmthw.hingegate.ui.home.AboutLinks
import org.junit.Test
import java.net.URI
import kotlin.test.assertEquals

class AboutLinksTest {

    @Test
    fun `the GitHub card opens Cris's profile over https`() {
        val uri = URI(AboutLinks.GITHUB)
        assertEquals("https", uri.scheme)
        assertEquals("github.com", uri.host)
        assertEquals("/CrsMthw", uri.path)
    }

    @Test
    fun `the coffee button opens Cris's Buy Me a Coffee page over https`() {
        val uri = URI(AboutLinks.BUY_ME_A_COFFEE)
        assertEquals("https", uri.scheme)
        assertEquals("buymeacoffee.com", uri.host)
        assertEquals("/crsmthw", uri.path)
    }
}
