package ru.mars.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThumbnailUrlResolverTest {

    @Test fun resolvesServerRelativeThumbFromOriginWithoutDoubleMusic() {
        assertEquals(
            "https://marscore.ru/music/api/thumb?v=RNT0MOm6e_w",
            ThumbnailUrlResolver.resolve("/music/api/thumb?v=RNT0MOm6e_w")
        )
    }

    @Test fun resolvesRelativePathWithoutLeadingSlash() {
        assertEquals(
            "https://marscore.ru/api/thumb?v=abc",
            ThumbnailUrlResolver.resolve("api/thumb?v=abc")
        )
    }

    @Test fun resolvesProtocolRelativeUrlWithOriginScheme() {
        assertEquals(
            "https://cdn.marscore.ru/cover.jpg",
            ThumbnailUrlResolver.resolve("//cdn.marscore.ru/cover.jpg")
        )
    }

    @Test fun keepsAbsoluteHttpAndHttpsUrls() {
        assertEquals(
            "https://i.ytimg.com/vi/abc/hqdefault.jpg",
            ThumbnailUrlResolver.resolve("https://i.ytimg.com/vi/abc/hqdefault.jpg")
        )
        assertEquals(
            "http://example.com/cover.jpg",
            ThumbnailUrlResolver.resolve("http://example.com/cover.jpg")
        )
    }

    @Test fun rejectsDangerousSchemes() {
        assertNull(ThumbnailUrlResolver.resolve("javascript:alert(1)"))
        assertNull(ThumbnailUrlResolver.resolve("data:image/png;base64,AAA"))
        assertNull(ThumbnailUrlResolver.resolve("file:///etc/passwd"))
        assertNull(ThumbnailUrlResolver.resolve("content://media/external/images/1"))
        assertNull(ThumbnailUrlResolver.resolve("intent://marscore.ru/#Intent;end"))
    }

    @Test fun rejectsBlankAndNull() {
        assertNull(ThumbnailUrlResolver.resolve(null))
        assertNull(ThumbnailUrlResolver.resolve(""))
        assertNull(ThumbnailUrlResolver.resolve("   "))
    }

    @Test fun honorsCustomOriginAndTrimsSlashesAndWhitespace() {
        assertEquals(
            "https://stage.marscore.ru/music/api/thumb?v=1",
            ThumbnailUrlResolver.resolve("  /music/api/thumb?v=1  ", "https://stage.marscore.ru/")
        )
        assertNull(ThumbnailUrlResolver.resolve("/music/api/thumb?v=1", "not-a-url"))
    }
}
