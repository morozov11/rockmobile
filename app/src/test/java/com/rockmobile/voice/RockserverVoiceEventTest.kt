package com.rockmobile.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RockserverVoiceEventTest {
    @Test fun parsesReadyTranscriptResultAndNoMatch() {
        assertNull(parseRockserverVoiceEvent("""{"type":"ready","request_id":"r","audio_format":"pcm_s16le","sample_rate_hz":16000}""") {})
        var transcript = ""
        assertNull(parseRockserverVoiceEvent("""{"type":"transcript","request_id":"r","transcript":"включи джаз","is_final":false}""") { transcript = it })
        assertEquals("включи джаз", transcript)
        val result = parseRockserverVoiceEvent("""{"type":"result","request_id":"r","transcript":"включи джаз","normalized_query":{"original":"x","locale":"ru-RU","terms":[],"tags":[],"language":null,"country_code":null},"selected_station":{"id":"jazz","name":"Quiet Jazz","stream_url":"https://example.test/jazz","tags":["jazz"],"country_code":"US","language":"en","codec":"MP3","bitrate_kbps":128,"homepage_url":null},"stations":[]}""") {}
        assertEquals("Quiet Jazz", (result as VoiceResolution.StationMatch).selected.name)
        val noMatch = parseRockserverVoiceEvent("""{"type":"result","request_id":"r","transcript":"ничего","normalized_query":{},"selected_station":null,"stations":[]}""") {}
        assertEquals(VoiceResolution.NoMatch("ничего"), noMatch)
    }

    @Test fun rejectsErrorsMalformedAndUnknownEvents() {
        try { parseRockserverVoiceEvent("""{"type":"error","code":"search_timeout","message":"slow","request_id":"r","details":{}}""") {}; throw AssertionError("expected exception") }
        catch (error: RockserverVoiceException) { assertEquals("search_timeout", error.code) }
        try { parseRockserverVoiceEvent("""{"type":"result","transcript":"x","selected_station":{"id":"x"},"stations":[]}""") {}; throw AssertionError("expected exception") }
        catch (_: IllegalArgumentException) { }
        try { parseRockserverVoiceEvent("""{"type":"pause"}""") {}; throw AssertionError("expected exception") }
        catch (_: IllegalArgumentException) { assertTrue(true) }
    }
}
