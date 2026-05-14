package com.whosup.packages.richinput

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure JUnit tests for the MIME-type → file-extension mapping used when
 * caching paste/keyboard rich content. The mapping is a pure string lookup,
 * so no Android runtime or Robolectric is needed.
 *
 * This is the first native-side test in the codebase; the harness it
 * proves out (testImplementation deps + module-internal visibility for
 * the helper) is what `docs/known-issues.md` issue #6 was tracking.
 */
class MimeTypeMappingTest {

    @Test
    fun mapsGif() {
        assertEquals("gif", RichChatInputView.mimeTypeToExtension("image/gif"))
    }

    @Test
    fun mapsWebp() {
        assertEquals("webp", RichChatInputView.mimeTypeToExtension("image/webp"))
    }

    @Test
    fun mapsPng() {
        assertEquals("png", RichChatInputView.mimeTypeToExtension("image/png"))
    }

    @Test
    fun mapsJpeg() {
        assertEquals("jpg", RichChatInputView.mimeTypeToExtension("image/jpeg"))
    }

    @Test
    fun mapsJpgAlias() {
        // Some keyboards advertise the legacy "image/jpg" instead of the
        // standard "image/jpeg". Both must resolve to .jpg.
        assertEquals("jpg", RichChatInputView.mimeTypeToExtension("image/jpg"))
    }

    @Test
    fun mapsSvg() {
        assertEquals("svg", RichChatInputView.mimeTypeToExtension("image/svg+xml"))
    }

    @Test
    fun mapsMp4() {
        assertEquals("mp4", RichChatInputView.mimeTypeToExtension("video/mp4"))
    }

    @Test
    fun mapsWebm() {
        assertEquals("webm", RichChatInputView.mimeTypeToExtension("video/webm"))
    }

    @Test
    fun maps3gp() {
        assertEquals("3gp", RichChatInputView.mimeTypeToExtension("video/3gpp"))
    }

    @Test
    fun mapsMkv() {
        assertEquals("mkv", RichChatInputView.mimeTypeToExtension("video/x-matroska"))
    }

    @Test
    fun unknownMimeTypeFallsBackToBin() {
        assertEquals("bin", RichChatInputView.mimeTypeToExtension("application/octet-stream"))
    }

    @Test
    fun emptyStringFallsBackToBin() {
        assertEquals("bin", RichChatInputView.mimeTypeToExtension(""))
    }
}
