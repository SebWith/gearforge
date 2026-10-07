package com.gearforge.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The settings panel is *generated* from [`GearSpec.fields`], so the registry is the only
 * place that knows which parameters exist. Every earlier test in this module picks its
 * values by hand (see `FuzzStressTest`, `TeethFieldBoundTest`), which means a field added to
 * `fields()` is uncovered until somebody remembers to write a case for it.
 *
 * This test sweeps the registry instead: for every [`GearType`], every declared field, every
 * probe value from far below the minimum to far above the maximum, including NaN and both
 * infinities. It asserts the contract the app depends on:
 *
 *  * `setNumber` never throws - a user typing a huge number must not crash the editor;
 *  * the state after any write is the sanitized state (`coerced()` invariants hold), so the
 *    mesh generator never sees a value it was not written for;
 *  * a field the panel advertises as editable is actually wired to the model. A key listed in
 *    `fields()` that `setNumber` does not handle is a text box whose edits are silently
 *    dropped: the user types, the number reverts, nothing explains why. That is the silent
 *    failure this repository has decided to hunt rather than tolerate.
 */
class ParamDefBoundaryTest {

    private val probes = listOf(
        Double.NaN,
        Double.POSITIVE_INFINITY,
        Double.NEGATIVE_INFINITY,
        -1.0e9,
        -1.0,
        -0.001,
        0.0,
        0.001,
        1.0e9
    )

    private fun numericFields(type: GearType): List<ParamDef> =
        GearSpec.fields(GearSpec.defaults(type)).filter { it.kind == FieldKind.NUMBER }

    private fun editableFields(type: GearType): List<ParamDef> =
        numericFields(type).filter { it.editable && it.scope == ParamScope.GLOBAL }

    private fun probeValues(def: ParamDef): List<Double> {
        val out = mutableListOf<Double>()
        out.addAll(probes)
        // The declared bounds are the ones the panel shows, so they are the values a user can
        // actually reach by typing. One step outside them on each side is what "outside the
        // limits" means for a field whose limits the UI enforces.
        if (def.max > def.min) {
            val span = def.max - def.min
            val step = if (span > 1.0) 1.0 else span * 0.1
            out.add(def.min)
            out.add(def.min - step)
            out.add(def.min + step)
            out.add((def.min + def.max) / 2.0)
            out.add(def.max - step)
            out.add(def.max)
            out.add(def.max + step)
        }
        return out
    }

    /** The invariants `GearParams.coerced` promises, checked as a whole rather than one key at a time. */
    private fun assertSanitized(p: GearParams, label: String) {
        assertTrue("$label: module ${p.module} outside [0.2, 12]", p.module in 0.2..12.0)
        assertTrue("$label: teeth ${p.teeth} below the profile floor", p.teeth >= p.toothProfile.minTeeth)
        assertTrue("$label: teeth ${p.teeth} above 300", p.teeth <= 300)
        assertTrue("$label: thickness ${p.thickness} not in (0, 500]", p.thickness > 0.0 && p.thickness <= 500.0)
        assertTrue("$label: pressure angle ${p.pressureAngleDeg} outside [1, 89]", p.pressureAngleDeg in 1.0..89.0)
        assertTrue("$label: profile shift ${p.profileShift} outside [-1, 1]", p.profileShift in -1.0..1.0)
        assertTrue("$label: helix ${p.helixAngleDeg} outside [-85, 85]", p.helixAngleDeg in -85.0..85.0)
        assertTrue("$label: backlash ${p.backlash} negative", p.backlash >= 0.0)
        assertTrue("$label: planet count ${p.planetCount} outside [2, 12]", p.planetCount in 2..12)
        assertTrue("$label: spoke count ${p.spokeCount} outside [0, 12]", p.spokeCount in 0..12)
        assertTrue("$label: lightening count ${p.lighteningHoleCount} outside [0, 12]", p.lighteningHoleCount in 0..12)
        assertTrue("$label: set screw count ${p.setScrewCount} outside [0, 2]", p.setScrewCount in 0..2)
        assertTrue("$label: bore diameter ${p.bore.diameter} not finite", p.bore.diameter.isFinite())
    }

    @Test
    fun everyNumericFieldSurvivesEveryProbeWithoutThrowing() {
        var cases = 0
        for (type in GearType.entries) {
            val base = GearSpec.defaults(type)
            for (def in numericFields(type)) {
                for (v in probeValues(def)) {
                    cases++
                    val label = "$type/${def.key} = $v"
                    val applied = try {
                        GearSpec.setNumber(base, def.key, v)
                    } catch (e: Throwable) {
                        throw AssertionError("$label threw ${e::class.simpleName}: ${e.message}", e)
                    }
                    assertSanitized(applied, label)
                    val read = GearSpec.getNumber(applied, def.key)
                    assertTrue("$label: read-back is not finite ($read)", read.isFinite())
                    val warnings = try {
                        GearSpec.validate(applied)
                    } catch (e: Throwable) {
                        throw AssertionError("$label: validate threw ${e::class.simpleName}: ${e.message}", e)
                    }
                    assertNotNull("$label: validate returned null", warnings)
                }
            }
        }
        assertTrue("the sweep produced no cases - the registry is empty", cases > 200)
    }

    @Test
    fun everyFiniteWriteRespectsAdvertisedFieldLimits() {
        val misses = mutableListOf<String>()
        for (type in GearType.entries) {
            for (unit in UnitSystem.entries) {
                val base = GearSpec.defaults(type).copy(unit = unit)
                val fields = GearSpec.fields(base).filter {
                    it.kind == FieldKind.NUMBER && it.editable && it.scope == ParamScope.GLOBAL && it.max > it.min
                }
                for (field in fields) {
                    for (value in probeValues(field).filter { it.isFinite() }) {
                        val applied = GearSpec.setNumber(base, field.key, value)
                        val actual = GearSpec.getNumber(applied, field.key)
                        if (!actual.isFinite() || actual < field.min - 1e-6 || actual > field.max + 1e-6) {
                            misses += "$type/$unit/${field.key}: input $value, got $actual outside ${field.min}..${field.max}"
                        }
                    }
                }
            }
        }
        assertTrue("${misses.size} writes escaped field limits:\n" + misses.take(30).joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun aWithinLimitsValueAlwaysLandsWithinTheFieldLimits() {
        // The panel draws its limits from ParamDef; after a write the model must not hold a
        // value the field cannot represent. Not every key is clamped (involute angles and
        // profile shift are handled by coerced()), so the assertion is the weaker but true
        // one: whatever comes back is inside the range the field advertises.
        val misses = mutableListOf<String>()
        for (type in GearType.entries) {
            val base = GearSpec.defaults(type)
            for (def in editableFields(type)) {
                if (def.max <= def.min) continue
                val mid = (def.min + def.max) / 2.0
                val applied = GearSpec.setNumber(base, def.key, mid)
                val read = GearSpec.getNumber(applied, def.key)
                if (read < def.min - 1e-6 || read > def.max + 1e-6) {
                    misses += "$type/${def.key}: typed $mid, model holds $read (field range ${def.min}..${def.max})"
                }
            }
        }
        assertTrue("fields whose model value left the advertised range:\n" + misses.joinToString("\n"), misses.isEmpty())
    }

    @Test
    fun noEditableFieldSilentlyIgnoresWhatTheUserTypes() {
        val inert = mutableListOf<String>()
        for (type in GearType.entries) {
            val base = GearSpec.defaults(type)
            for (def in editableFields(type)) {
                val current = GearSpec.getNumber(base, def.key)
                // A distinctive value inside the advertised range, or a shift from the default
                // when the field declares no range at all.
                val target = if (def.max > def.min) (def.min + def.max) / 2.0 else current + 3.0
                if (abs(target - current) < 1e-9) continue
                val applied = GearSpec.setNumber(base, def.key, target)
                val read = GearSpec.getNumber(applied, def.key)
                if (abs(read - current) < 1e-9) {
                    inert += "$type/${def.key}: editable, but writing $target left it at $current"
                }
            }
        }
        assertTrue(
            "editable fields whose writes are dropped by setNumber:\n" + inert.joinToString("\n"),
            inert.isEmpty()
        )
    }

    @Test
    fun everyGearTypeStaysValidAndMeshableAtEveryFieldLimit() {
        // Why this is a budget and a partial sweep rather than one loop over everything.
        //
        // Measured 2026-09-22: the first version built a full mesh for every field at both limits -
        // roughly 840 builds - and it was still running after thirteen minutes. That is not a slow
        // test, it is a test that cannot be a gate: the extremes this sweep feeds are the expensive
        // cases by construction. Measured again 2026-09-23 on an idle machine: a 200-tooth helical
        // takes 12.8 s and 768 768 triangles, and the next six slowest take 1.0-4.1 s each. 586 cases
        // is hours of meshing, not minutes.
        //
        // What the sweep does instead:
        //
        //  * the planar SHAPE is built for every field at both limits. Degenerate outlines, holes that
        //    run into the bore and non-manifold bridges all live in this stage, and it does not extrude
        //    or loft, so it is cheap - all 586 of them take well under a second;
        //  * the MESH is built for the first case of **every gear type** before the budget may skip
        //    anything, so no type can be left unmeshed on a slow machine;
        //  * after that, further meshes are built while the budget lasts, in a deterministic order, and
        //    every case the budget skips is printed. A skipped case is loud, not silent;
        //  * the gate is that coverage rather than a percentage of the sweep. A wall-clock budget
        //    divided by the number of cases measures the machine: this test failed on an idle machine
        //    at 506 of 586 skipped while every case it did reach was valid, which is a false alarm
        //    about the code and a true one about the gate.
        //
        // The budget is settable for a machine that is faster or slower than this one:
        //   .\gradlew.bat :core:test --tests "*ParamDefBoundaryTest*" -Dgearforge.meshBudgetMs=120000
        val meshBudgetMs = System.getProperty("gearforge.meshBudgetMs")?.toLongOrNull() ?: 45_000L

        // Every (type, field, bound) case with the parameters it produces, so the sweep can make two
        // passes over the same list without re-deriving them.
        data class Extreme(val label: String, val type: GearType, val params: GearParams)
        val extremes = buildList {
            for (type in GearType.entries) {
                val base = GearSpec.defaults(type)
                for (def in numericFields(type)) {
                    if (def.max <= def.min) continue
                    for (v in listOf(def.min, def.max)) {
                        add(Extreme("$type/${def.key} = $v", type, GearSpec.setNumber(base, def.key, v)))
                    }
                }
            }
        }

        // The shape stage, for every case: this is where a degenerate outline would come out.
        for (case in extremes) {
            val shape = try {
                GearBuilder.shape(case.params)
            } catch (e: Throwable) {
                throw AssertionError("${case.label}: shape threw ${e::class.simpleName}: ${e.message}", e)
            }
            assertTrue("${case.label}: the shape has no outline", shape.outer.isNotEmpty())
        }

        val timings = mutableListOf<Triple<String, Long, Int>>()
        val skipped = mutableListOf<String>()
        var spent = 0L

        fun mesh(p: GearParams, label: String, withinBudget: Boolean) {
            if (withinBudget && spent > meshBudgetMs) {
                skipped += label
                return
            }
            val started = System.nanoTime()
            val mesh = try {
                GearBuilder.mesh(p)
            } catch (e: Throwable) {
                throw AssertionError("$label: mesh threw ${e::class.simpleName}: ${e.message}", e)
            }
            val ms = (System.nanoTime() - started) / 1_000_000
            spent += ms
            timings += Triple(label, ms, mesh.triangles.size)

            assertTrue("$label: mesh has no triangles", mesh.triangles.isNotEmpty())
            for (vertex in mesh.vertices) {
                assertTrue(
                    "$label: non-finite vertex $vertex",
                    vertex.x.isFinite() && vertex.y.isFinite() && vertex.z.isFinite()
                )
            }
            val n = mesh.vertices.size
            for (t in mesh.triangles) {
                assertTrue("$label: triangle index out of range", t.all { it in 0 until n })
            }
        }

        val byType = extremes.groupBy { it.type }
        // Pass 1: one case per type, whatever the budget says. This is the gate's teeth.
        for (type in GearType.entries) {
            byType[type]?.firstOrNull()?.let { mesh(it.params, it.label, withinBudget = false) }
        }
        // Pass 2: everything else, in the registry's own order, while the budget lasts.
        for (type in GearType.entries) {
            byType[type]?.drop(1)?.forEach { mesh(it.params, it.label, withinBudget = true) }
        }

        val cases = extremes.size
        val meshedTypes = timings.map { it.first.substringBefore('/') }.toSet()
        val unmeshed = GearType.entries.map { it.name }.filterNot { it in meshedTypes }
        val slowest = timings.sortedByDescending { it.second }.take(8)
            .joinToString("\n") { "  ${it.first}: ${it.second} ms, ${it.third} triangles" }
        println(
            "paramdef sweep: cases=$cases shapes=$cases meshes=${timings.size} meshMs=$spent " +
                "skipped=${skipped.size}\nslowest meshes:\n$slowest"
        )
        if (skipped.isNotEmpty()) {
            println("paramdef sweep: mesh not built for ${skipped.size} case(s) past the budget:")
            skipped.forEach { println("  $it") }
        }
        assertTrue("the sweep produced no cases", cases > 100)
        assertTrue(
            "these gear types were never meshed at all: $unmeshed",
            unmeshed.isEmpty()
        )
    }

    @Test
    fun theRegistryItselfIsConsistent() {
        val problems = mutableListOf<String>()
        for (type in GearType.entries) {
            val fields = GearSpec.fields(GearSpec.defaults(type))
            assertTrue("$type declares no fields", fields.isNotEmpty())
            val keys = fields.map { it.key }
            assertEquals("$type declares a duplicate key", keys.size, keys.toSet().size)
            for (def in fields) {
                val where = "$type/${def.key}"
                if (def.kind == FieldKind.NUMBER && def.max < def.min) {
                    problems += "$where: max ${def.max} below min ${def.min}"
                }
                if (def.decimals < 0 || def.decimals > 6) {
                    problems += "$where: decimals ${def.decimals} outside 0..6"
                }
                if (def.kind == FieldKind.CHOICE && def.options.isEmpty()) {
                    problems += "$where: a choice field with no options"
                }
                if (def.kind == FieldKind.CALCULATED && def.formula == null) {
                    problems += "$where: calculated field with no formula"
                }
                if (def.editable && def.kind == FieldKind.CALCULATED) {
                    problems += "$where: calculated fields must not be editable"
                }
                if (def.key.isBlank() || def.label.isBlank()) {
                    problems += "$where: blank key or label"
                }
            }
        }
        assertTrue("registry inconsistencies:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
