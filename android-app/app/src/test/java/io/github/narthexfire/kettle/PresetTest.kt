package io.github.narthexfire.kettle

import io.github.narthexfire.kettle.data.DEFAULT_PRESETS
import io.github.narthexfire.kettle.data.KettleConfig
import io.github.narthexfire.kettle.data.Preset
import io.github.narthexfire.kettle.data.PresetLogic
import io.github.narthexfire.kettle.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Robolectric runner so org.json (used by the DataStore JSON encoding) is the real implementation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PresetTest {
    @Test fun defaults() {
        assertEquals(
            listOf("Coffee" to 88, "Green Tea" to 75, "White Tea" to 80, "Oolong" to 85, "Hot Chocolate" to 70, "Warm" to 60),
            DEFAULT_PRESETS.map { it.name to it.temp },
        )
        assertEquals(6, PresetLogic.COUNT)
        assertEquals(listOf("cup", "leaf", "flower", "pot", "mug", "flame"), DEFAULT_PRESETS.map { it.icon })
        DEFAULT_PRESETS.forEach { assertEquals(0, it.keepWarm) }
        KettleConfig.DEFAULTS.forEach { assertEquals(DEFAULT_PRESETS, it.presets) }
        assertEquals(-1, DEFAULT_PRESETS.indexOfFirst { it.name == "Boil" })
    }

    @Test fun persistenceRoundTripPerKettleWithKeepWarm() {
        val list = TestKettles.list.toMutableList()
        val mine = listOf(
            Preset("Sencha", 70, 5, "leaf"), Preset("Jasmine", 80, 0, "flower"), Preset("Oolong", 86, 10, "pot"),
            Preset("Coffee", 90, 30, "cup"), Preset("Cocoa", 68, 0, "mug"), Preset("Warm", 60, 20, "flame"),
        )
        list[0] = list[0].copy(presets = mine)
        val back = SettingsRepository.decode(SettingsRepository.encode(list))
        assertEquals(mine, back[0].presets)
        assertEquals(10, back[0].presets[2].keepWarm)
        assertEquals(DEFAULT_PRESETS, back[1].presets) // other kettles keep their own (default) presets
    }

    @Test fun oldSettingsWithoutPresetsGetDefaults() {
        val old = """[{"id":"kitchen","name":"Kitchen-Kettle","ip":"192.168.1.100","port":6002,"imei":"GD0-00000-00ab"}]"""
        assertEquals(DEFAULT_PRESETS, SettingsRepository.decode(old)[0].presets)
    }

    @Test fun fourSlotDataMigratesToSix() {
        val olderData = """[{"id":"kitchen","name":"Kitchen-Kettle","ip":"192.168.1.100","port":6002,"imei":"GD0-00000-00ab",
            "presets":[{"name":"Sencha","temp":72},{"name":"White tea","temp":80},{"name":"Oolong","temp":85},{"name":"Coffee","temp":88}]}]"""
        val p = SettingsRepository.decode(olderData)[0].presets
        assertEquals(6, p.size)
        // Style migration: Coffee to slot 1, the others keep their order; icons follow their preset.
        assertEquals(listOf("Coffee", "Sencha", "White Tea", "Oolong", "Hot Chocolate", "Warm"), p.map { it.name })
        assertEquals(Preset("Sencha", 72, 0, "leaf"), p[1])
        assertEquals("cup", p[0].icon)
        assertEquals(DEFAULT_PRESETS[4], p[4])
        assertEquals(DEFAULT_PRESETS[5], p[5])
    }

    @Test fun titleCaseMigrationOnlyTouchesExactOldDefaults() {
        val json = """[{"id":"kitchen","name":"M","presets":[{"name":"Green tea","temp":75},{"name":"green tea","temp":76},
            {"name":"Hot chocolate","temp":70},{"name":"My White tea","temp":80},{"name":"Coffee","temp":88},{"name":"Warm","temp":60}]}]"""
        val p = SettingsRepository.decode(json)[0].presets
        assertEquals(listOf("Coffee", "Green Tea", "green tea", "Hot Chocolate", "My White tea", "Warm"), p.map { it.name })
        // User input is never transformed: saving "Green tea" by hand keeps it as typed.
        assertEquals("Green tea", PresetLogic.saveTo(p, 0, "Green tea", 75, 0, 95)[0].name)
    }

    @Test fun keepWarmNeedsSixtyDegrees() {
        assertEquals(false, io.github.narthexfire.kettle.data.TempLogic.keepWarmAllowed(59))
        assertEquals(true, io.github.narthexfire.kettle.data.TempLogic.keepWarmAllowed(60))
        assertEquals(0, io.github.narthexfire.kettle.data.TempLogic.effectiveKeepWarm(55, 10))
        assertEquals(10, io.github.narthexfire.kettle.data.TempLogic.effectiveKeepWarm(60, 10))
        // A preset below 60 cannot store keep warm, however it is created.
        assertEquals(0, PresetLogic.normalize(listOf(Preset("Baby milk", 45, 10)))[0].keepWarm)
        assertEquals(0, PresetLogic.saveTo(DEFAULT_PRESETS, 5, "Cool", 50, 15, 95)[5].keepWarm)
        assertEquals(15, PresetLogic.saveTo(DEFAULT_PRESETS, 5, "Warm", 60, 15, 95)[5].keepWarm)
        val json = """[{"id":"m","name":"M","presets":[{"name":"Low","temp":50,"keepWarm":20}]}]"""
        assertEquals(0, SettingsRepository.decode(json)[0].presets[0].keepWarm)
    }

    @Test fun coffeeFirstMigrationRunsOnceAndKeepsValues() {
        // Older stored data: old order, no icons stored, no migration flag; Oolong has keep warm 10.
        val olderData = """[{"id":"kitchen","name":"M","presets":[{"name":"Green Tea","temp":74},{"name":"White Tea","temp":80},
            {"name":"Oolong","temp":85,"keepWarm":10},{"name":"coffee","temp":90,"keepWarm":5},{"name":"Hot Chocolate","temp":70},{"name":"Warm","temp":60}]}]"""
        val k = SettingsRepository.decode(olderData)[0]
        assertEquals(listOf("coffee", "Green Tea", "White Tea", "Oolong", "Hot Chocolate", "Warm"), k.presets.map { it.name })
        assertEquals(Preset("coffee", 90, 5, "cup"), k.presets[0]) // values and icon untouched
        assertEquals(Preset("Green Tea", 74, 0, "leaf"), k.presets[1])
        assertEquals(Preset("Oolong", 85, 10, "pot"), k.presets[3])
        assertEquals(true, k.presetOrderMigrated)

        // Runs once: after it is saved with the flag, a later manual order (Coffee third) is left alone.
        val manual = k.copy(presets = listOf(k.presets[1], k.presets[2], k.presets[0]) + k.presets.drop(3))
        val again = SettingsRepository.decode(SettingsRepository.encode(listOf(manual)))[0]
        assertEquals(manual.presets, again.presets)
        assertEquals("coffee", again.presets[2].name)
    }

    @Test fun coffeeFirstLeavesOrderAloneWithoutCoffee() {
        val json = """[{"id":"m","name":"M","presets":[{"name":"Sencha","temp":72},{"name":"Matcha","temp":78},{"name":"Rooibos","temp":95},
            {"name":"Barley","temp":90},{"name":"Cocoa","temp":70},{"name":"Warm","temp":60}]}]"""
        assertEquals(listOf("Sencha", "Matcha", "Rooibos", "Barley", "Cocoa", "Warm"), SettingsRepository.decode(json)[0].presets.map { it.name })
        assertEquals(DEFAULT_PRESETS, PresetLogic.coffeeFirst(DEFAULT_PRESETS)) // already first: unchanged
    }

    @Test fun normalizeFillsAndTrims() {
        val n = PresetLogic.normalize(listOf(Preset("  ", 10, 7), Preset("x".repeat(40), 120, 30)))
        assertEquals(6, n.size)
        assertEquals("Coffee", n[0].name) // blank name falls back to the slot's default
        assertEquals(40, n[0].temp)
        assertEquals(0, n[0].keepWarm) // 7 is not an allowed keep-warm option
        assertEquals(PresetLogic.MAX_NAME, n[1].name.length)
        assertEquals(100, n[1].temp)
        assertEquals(30, n[1].keepWarm)
        assertEquals(DEFAULT_PRESETS[2], n[2])
    }

    @Test fun presetRange40To100AndBoilLabel() {
        assertEquals(95, PresetLogic.clamp(95, 95)) // no boil-point clamp any more
        assertEquals(100, PresetLogic.clamp(120, 95))
        assertEquals(40, PresetLogic.clamp(30, 95))
        assertEquals(100, PresetLogic.plus(100, 95))
        assertEquals(93, PresetLogic.plus(92, 95))
        assertEquals(40, PresetLogic.minus(40, 95))
        assertEquals("92\u00B0", PresetLogic.label(Preset("Coffee", 92), 95))
        assertEquals("Boil", PresetLogic.label(Preset("Full boil", 100), 95))
        assertEquals("88\u00B0", PresetLogic.label(Preset("Coffee", 88), 95))
        assertEquals("85\u00B0 \u00B7 warm 10\u2032", PresetLogic.label(Preset("Oolong", 85, 10), 95))
        assertEquals(3, PresetLogic.indexFor(85, DEFAULT_PRESETS, 95))
        assertNull(PresetLogic.indexFor(100, DEFAULT_PRESETS, 95))
    }

    @Test fun saveFromSheetOverwritesTheRightSlot() {
        val out = PresetLogic.saveTo(DEFAULT_PRESETS, slot = 2, name = "Tieguanyin", target = 83, keepWarm = 10, boilPoint = 95)
        assertEquals(Preset("Tieguanyin", 83, 10, "flower"), out[2]) // keeps the slot's icon
        DEFAULT_PRESETS.indices.filter { it != 2 }.forEach { assertEquals(DEFAULT_PRESETS[it], out[it]) }
        // Blank name keeps the slot's name; a Boil target (100) is stored as 100.
        val boil = PresetLogic.saveTo(DEFAULT_PRESETS, slot = 5, name = " ", target = 100, keepWarm = 5, boilPoint = 95)
        assertEquals(Preset("Warm", 100, 5, "flame"), boil[5])
        assertEquals("Boil · warm 5′", PresetLogic.label(boil[5], 95))
    }
}
