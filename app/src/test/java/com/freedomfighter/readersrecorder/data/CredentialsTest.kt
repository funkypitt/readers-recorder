package com.freedomfighter.readersrecorder.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CredentialsTest {
    @Test fun buildWritesOnlyOurSection() {
        val root = JSONObject(Credentials.build("https://1.connect.kdrive.infomaniak.com", "Recordings", "me@x.ch", "pw"))
        assertEquals("readers-credentials", root.getString("format"))
        assertEquals(setOf("format", "version", "readers-recorder"), root.keys().asSequence().toSet())
        assertEquals("Recordings", root.getJSONObject("readers-recorder").getString("folder"))
    }

    @Test fun roundTrip() {
        val i = Credentials.read(Credentials.build("https://srv", "Recordings", "u", "pw"))
        assertFalse(i.fromFallback); assertEquals(Credentials.Account("https://srv", "Recordings", "u", "pw"), i.account)
    }

    @Test fun ignoresOtherSectionsAndUnknownKeys() {
        val i = Credentials.read("""{"format": "readers-credentials", "version": 1,
            "readers-calendar": {"google": {"tokens": {}}}, "readers-notes": {"server": "https://n", "folder": "Notes"},
            "readers-recorder": {"server": "https://r", "folder": "Rec", "username": "u", "password": "p", "kind": "memo"}}""")
        assertEquals(Credentials.Account("https://r", "Rec", "u", "p"), i.account); assertFalse(i.fromFallback)
    }

    @Test fun fallbackTakesNotesServerAndLoginNotFolder() {
        val i = Credentials.read("""{"format": "readers-credentials", "version": 1, "readers-notes": {"server": "https://k", "folder": "Notes", "username": "n", "password": "np"}}""")
        assertTrue(i.fromFallback)
        assertEquals("https://k", i.account.server); assertNull(i.account.folder); assertEquals("n", i.account.username)
    }

    @Test(expected = Credentials.NotCredentials::class) fun refusesForeignJson() { Credentials.read("""{"format": "something-else", "readers-recorder": {"server": "x"}}""") }
    @Test(expected = Credentials.NothingForUs::class) fun nothingForUs() { Credentials.read("""{"format": "readers-credentials", "readers-tasks": {"url": "x"}}""") }
}
