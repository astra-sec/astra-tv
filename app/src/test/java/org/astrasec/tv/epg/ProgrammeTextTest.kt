package org.astrasec.tv.epg

import org.junit.Assert.assertEquals
import org.junit.Test

class ProgrammeTextTest {
    @Test fun decodesCommonNamedAndNumericEntities() {
        assertEquals("Today's \"节目\" & <预告> ", ProgrammeText.unescape(
            "Today&apos;s &quot;节目&quot; &amp; &lt;预告&gt;&nbsp;",
        ))
        assertEquals("' ' 📺", ProgrammeText.unescape("&#39; &#x27; &#X1F4FA;"))
    }

    @Test fun preservesPlainTextUnknownEntitiesAndInvalidCodePoints() {
        val title = "新闻 <直播> A&B &unknown; &apos &#0; &#xD800; &#x110000; &#999999999;"
        assertEquals(title, ProgrammeText.unescape(title))
    }

    @Test fun decodesOnlyOneLayerWithoutChangingStoredOrCachedTitle() {
        val programme = EpgProgramme(0, 1, "&amp;apos; &apos;", "test")
        assertEquals("&apos; '", programme.displayTitle)
        assertEquals("&apos; '", programme.displayTitle)
        assertEquals("&amp;apos; &apos;", programme.title)
    }
}
