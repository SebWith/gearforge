package com.gearforge.app

import com.gearforge.core.GearSpec
import com.gearforge.core.GearType
import com.gearforge.core.ParamGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsPanelRowsTest {
    private val defs = GearSpec.fields(GearSpec.defaults(GearType.SPUR))

    @Test fun formattedDecimalsDoNotRecommitUnchangedFloatValues() {
        for (value in listOf(0.1, 0.38, 0.15, 1.6, 0.02, 24.0)) {
            assertFalse("Unchanged Float value: $value", numberRowValueChanged(value, value.toFloat()))
        }
    }

    @Test fun numericChangesStillCommitInEitherDirection() {
        assertTrue(numberRowValueChanged(24.0, 20f))
        assertTrue(numberRowValueChanged(20.0, 24f))
        assertTrue(numberRowValueChanged(0.15, 0.1f))
    }

    @Test fun sheetViewportExcludesTheOffscreenPartWithoutChangingAnchors() {
        assertEquals(900, settingsPanelViewportHeight(2000, 1100f))
        assertEquals(2000, settingsPanelViewportHeight(2000, 0f))
        assertEquals(1, settingsPanelViewportHeight(2000, 2200f))
        assertEquals(1000, settingsPanelViewportHeight(2000, null))
        assertEquals(1000, settingsPanelViewportHeight(2000, Float.NaN))
    }

    @Test fun eachFieldHasItsOwnStableItemForEveryGearType() {
        for (type in GearType.entries) {
            val fields = GearSpec.fields(GearSpec.defaults(type))
            val rows = settingsPanelRows(fields, ParamGroup.entries.toSet(), listOf("diameter"), true)
            assertEquals(rows.size, rows.map { it.key }.toSet().size)
            assertEquals(fields.map { it.key }.toSet(), rows.filterIsInstance<SettingsPanelRow.Field>().map { it.def.key }.toSet())
        }
    }

    @Test fun collapsedGroupsContainOnlyHeaders() {
        val rows = settingsPanelRows(defs, emptySet(), listOf("diameter"), true)
        assertTrue(rows.all { it is SettingsPanelRow.Header })
        assertEquals(defs.map { it.group }.toSet() + ParamGroup.RESULTS, rows.map { it.group }.toSet())
    }

    @Test fun togglingGeometryPreservesOtherKeysAndToothTarget() {
        val expanded = settingsPanelRows(defs, ParamGroup.entries.toSet(), emptyList(), true)
        val collapsed = settingsPanelRows(defs, ParamGroup.entries.toSet() - ParamGroup.GEOMETRY, emptyList(), true)
        assertEquals(
            expanded.filter { it.group != ParamGroup.GEOMETRY }.map { it.key },
            collapsed.filter { it.group != ParamGroup.GEOMETRY }.map { it.key }
        )
        val target = collapsed.indexOfFirst { it.key == "header:TEETH" }
        assertTrue(target >= 0)
        assertTrue(target < expanded.indexOfFirst { it.key == "header:TEETH" })
        assertTrue(collapsed.drop(target + 1).any { it is SettingsPanelRow.Teeth })
    }

    @Test fun toothEditorIsOptionalAndRequiresAnExpandedSection() {
        assertFalse(settingsPanelRows(defs, ParamGroup.entries.toSet(), emptyList(), false).any { it is SettingsPanelRow.Teeth })
        assertFalse(settingsPanelRows(defs, emptySet(), emptyList(), true).any { it is SettingsPanelRow.Teeth })
    }

    @Test fun resultsKeepTheirOrderAndIndividualKeys() {
        val keys = listOf("outer_diameter", "pitch_diameter", "mass")
        val rows = settingsPanelRows(defs, setOf(ParamGroup.RESULTS), keys, false)
        assertEquals(keys, rows.filterIsInstance<SettingsPanelRow.Result>().map { it.resultKey })
        assertTrue(rows.last() is SettingsPanelRow.End)
    }
}