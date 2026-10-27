package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[34])
class CompleteBackupTest {
    private fun library()=LibraryBackup.encode(BackupData(emptyList(),emptyList(),emptySet(),emptyList(),BackupSettings(720,1f,true,ContentType.COMIC,VideoFrameMode.FIT)))
    @Test fun completeEnvelopeIsOptInAndRoundTripsSourceRecords() {
        val app=RuntimeEnvironment.getApplication()
        val source=ThirdSource("one","测试片源","https://example.invalid/api.php?token=private-fixture")
        ThirdStore(app).mergeSources(listOf(source))
        val tools=FeaturePreferences(app).export()
        val basic=PortableBackupCodec.encode(library(),tools,1000)
        assertFalse(basic.contains("private-fixture"));assertNull(PortableBackupCodec.decode(basic).sources)
        val full=PortableBackupCodec.encode(library(),tools,1000,SourceLibraryBackup(app).exportState())
        assertTrue(full.contains("private-fixture"))
        assertNotNull(PortableBackupCodec.decode(full).sources)
        assertEquals(full,PortableBackupCodec.sanitize(full))
    }
    @Test fun malformedSourceOrToolsAreRejectedBeforeAnyRestore() {
        val app=RuntimeEnvironment.getApplication();val prefs=FeaturePreferences(app);prefs.addGroup("保留")
        val full=JSONObject(PortableBackupCodec.encode(library(),prefs.export(),1000))
        full.put("version",2).put("sources","{\"version\":99}")
        assertTrue(runCatching{PortableBackupCodec.decode(full.toString())}.isFailure)
        assertEquals(listOf("保留"),prefs.groups())
        val bad=JSONObject().put("version",1).put("seekStep",99).put("groups",org.json.JSONArray(listOf("不能写入")))
        assertTrue(runCatching{prefs.restore(bad.toString(),true)}.isFailure)
        assertEquals(listOf("保留"),prefs.groups())
    }
    @Test fun malformedNestedToolsCannotSmuggleExtraFieldsOrPartiallyWrite() {
        val app=RuntimeEnvironment.getApplication();val prefs=FeaturePreferences(app);prefs.addGroup("保留")
        val payload=JSONObject().put("version",1).put("groups",org.json.JSONArray(listOf("新组")))
            .put("skips",JSONObject().put("series",JSONObject().put("intro",10).put("outro",0).put("cookie","not-a-tool")))
        assertTrue(runCatching { prefs.restore(payload.toString(),true) }.isFailure)
        assertEquals(listOf("保留"),prefs.groups())
        assertTrue(runCatching { FeaturePreferences.validate(JSONObject().put("version",1).put("keys",JSONObject().put("4","TOOLS")).toString()) }.isFailure)
    }
    @Test fun globalPreferencesRestoreOnlyWhenExplicitlySelected() {
        val app=RuntimeEnvironment.getApplication();val prefs=FeaturePreferences(app)
        val raw=JSONObject().put("version",1).put("seekStep",30).put("holdSpeed",4.0).put("theme","PAPER").put("nextSeason",false).put("autoSkipIncompatible",true).toString()
        prefs.restore(raw);assertEquals(10,prefs.seekStepSeconds);assertTrue(prefs.nextSeason)
        prefs.restore(raw,true);assertEquals(30,prefs.seekStepSeconds);assertEquals(FeatureTheme.PAPER,prefs.theme);assertFalse(prefs.nextSeason);assertTrue(prefs.autoSkipIncompatible)
        assertNull(app.getSharedPreferences("feature-tools-v1",Context.MODE_PRIVATE).getString("accountCookie",null))
    }
}
