package com.gmodkit.tools.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class WorkshopDescriptionTest {

    @Test
    fun `link with a label keeps both parts`() {
        val text = cleanWorkshopDescription("See [url=https://example.com/a]the guide[/url] please")
        assertEquals("See the guide (https://example.com/a) please", text)
    }

    @Test
    fun `bare link tag shows its target`() {
        assertEquals(
            "https://example.com/a",
            cleanWorkshopDescription("[url=https://example.com/a][/url]")
        )
        assertEquals(
            "https://example.com/a",
            cleanWorkshopDescription("[url=https://example.com/a]")
        )
    }

    @Test
    fun `formatting tags are dropped but the words stay`() {
        val text = cleanWorkshopDescription("[b]Bold[/b] and [i]italic[/i] with [list][*]one[/list]")
        assertFalse(text.contains('['))
        assertEquals("Bold and italic with one", text)
    }

    @Test
    fun `entities are decoded`() {
        assertEquals("R&D <b> \"q\" é", cleanWorkshopDescription("R&amp;D &lt;b&gt; &quot;q&quot; &#233;"))
    }

    @Test
    fun `whitespace collapses without eating paragraphs`() {
        val text = cleanWorkshopDescription("line one   \n\n\n\nline two")
        assertEquals("line one\n\nline two", text)
    }

    @Test
    fun `plain text is untouched`() {
        assertEquals("A collection of entities.", cleanWorkshopDescription("A collection of entities."))
    }
}
