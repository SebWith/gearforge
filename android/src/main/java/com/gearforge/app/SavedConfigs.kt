package com.gearforge.app

import android.content.Context
import com.gearforge.core.BoreSpec
import com.gearforge.core.BoreType
import com.gearforge.core.GearParams
import com.gearforge.core.GearType
import com.gearforge.core.PrecisionLevel
import com.gearforge.core.ToothProfile
import com.gearforge.core.UnitSystem
import org.json.JSONObject

/** Persists user gear configurations as JSON in SharedPreferences. */
object SavedConfigs {

    private const val PREFS = "saved_configs"
    private const val MAP_KEY = "map"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun toJson(p: GearParams): String = JSONObject().apply {
        put("gearType", p.gearType.name)
        put("toothProfile", p.toothProfile.name)
        put("module", p.module)
        put("teeth", p.teeth)
        put("pressureAngle", p.pressureAngleDeg)
        put("thickness", p.thickness)
        put("backlash", p.backlash)
        put("profileShift", p.profileShift)
        put("helixAngle", p.helixAngleDeg)
        put("precision", p.precision.name)
        put("unit", p.unit.name)
        put("boreType", p.bore.type.name)
        put("boreDiameter", p.bore.diameter)
        put("dCut", p.bore.dCutFlatOffset)
        put("keyW", p.bore.keywayWidth)
        put("keyD", p.bore.keywayDepth)
        put("hex", p.bore.hexAcrossFlats)
        put("addendum", p.addendumCoef)
        put("dedendum", p.dedendumCoef)
        put("hubDiameter", p.hubDiameter)
        put("hubLength", p.hubLength)
        put("coneAngle", p.coneAngleDeg)
        put("pitchCone", p.pitchConeDeg)
        put("mountingDistance", p.mountingDistance)
        put("pinionTeeth", p.pinionTeeth)
        put("rackLength", p.rackLength)
        put("planetCount", p.planetCount)
        put("planetTeeth", p.planetTeeth)
        put("ringTeeth", p.ringTeeth)
        put("wormStarts", p.wormStarts)
        put("wheelTeeth", p.wheelTeeth)
        put("material", p.material)
        put("surfaceFinish", p.surfaceFinishUm)
        put("toleranceClass", p.toleranceClass)
        put("lubrication", p.lubrication)
        put("load", p.loadNm)
        put("speed", p.speedRpm)
        put("lifetime", p.lifetimeHours)
        put("safetyFactor", p.safetyFactor)
        // ---- asymmetric hub / grub screw / structure / markers / tolerances ----
        put("hubLeftLength", p.hubLeftLength)
        put("hubRightLength", p.hubRightLength)
        put("hubChamfer", p.hubChamfer)
        put("hubFillet", p.hubFillet)
        put("hubDraftAngle", p.hubDraftAngleDeg)
        put("setScrewCount", p.setScrewCount)
        put("setScrewThread", p.setScrewThread)
        put("setScrewAngle", p.setScrewAngleDeg)
        put("setScrewAngle2", p.setScrewAngle2Deg)
        put("setScrewDepth", p.setScrewDepth)
        put("setScrewAxialOffset", p.setScrewAxialOffset)
        put("rootFilletCoef", p.rootFilletCoef)
        put("transitionCoef", p.transitionCoef)
        put("tipChamfer", p.tipChamfer)
        put("tipRelief", p.tipRelief)
        put("rootRelief", p.rootRelief)
        put("lighteningHoleCount", p.lighteningHoleCount)
        put("lighteningHoleDiameter", p.lighteningHoleDiameter)
        put("lighteningHolePCD", p.lighteningHolePCD)
        put("spokeCount", p.spokeCount)
        put("spokeWidth", p.spokeWidth)
        put("indexMarkType", p.indexMarkType)
        put("indexMarkAngle", p.indexMarkAngleDeg)
        put("boreHoleTolerance", p.boreHoleTolerance)
        put("keywayTolerance", p.keywayTolerance)
        put("beltProfile", p.beltProfile)
        put("beltWidth", p.beltWidthMm)
        put("beltDriverTeeth", p.beltDriverTeeth)
        put("beltDrivenTeeth", p.beltDrivenTeeth)
        put("beltCenterDistance", p.beltCenterDistanceMm)
        put("beltTension", p.beltTensionN)
        put("beltBacklash", p.beltBacklashMm)
        put("beltFlangeCount", p.beltFlangeCount)
        put("beltIdlerCount", p.beltIdlerCount)
        val overrides = JSONObject()
        p.toothOverrides.forEach { (idx, o) ->
            val jo = JSONObject()
            o.leftPressureAngleDeg?.let { jo.put("la", it) }
            o.rightPressureAngleDeg?.let { jo.put("ra", it) }
            o.toothThickness?.let { jo.put("t", it) }
            o.addendumCoef?.let { jo.put("a", it) }
            o.dedendumCoef?.let { jo.put("d", it) }
            o.rootFilletCoef?.let { jo.put("rf", it) }
            o.tipChamfer?.let { jo.put("tc", it) }
            o.tipRelief?.let { jo.put("tr", it) }
            o.rootRelief?.let { jo.put("rr", it) }
            o.transitionCoef?.let { jo.put("tn", it) }
            overrides.put(idx.toString(), jo)
        }
        put("toothOverrides", overrides)
    }.toString()

    fun fromJson(json: String): GearParams? = try {
        val o = JSONObject(json)
        GearParams(
            gearType = GearType.valueOf(o.optString("gearType", "SPUR")),
            toothProfile = ToothProfile.valueOf(o.optString("toothProfile", "INVOLUTE")),
            module = o.optDouble("module", 1.0),
            teeth = o.optInt("teeth", 20),
            pressureAngleDeg = o.optDouble("pressureAngle", 20.0),
            thickness = o.optDouble("thickness", 6.0),
            backlash = o.optDouble("backlash", 0.1),
            profileShift = o.optDouble("profileShift", 0.0),
            helixAngleDeg = o.optDouble("helixAngle", 0.0),
            precision = PrecisionLevel.valueOf(o.optString("precision", "STANDARD")),
            unit = UnitSystem.valueOf(o.optString("unit", "MM")),
            bore = BoreSpec(
                type = BoreType.valueOf(o.optString("boreType", "ROUND")),
                diameter = o.optDouble("boreDiameter", 5.0),
                dCutFlatOffset = o.optDouble("dCut", 1.0),
                keywayWidth = o.optDouble("keyW", 2.0),
                keywayDepth = o.optDouble("keyD", 1.0),
                hexAcrossFlats = o.optDouble("hex", 6.0)
            ),
            addendumCoef = o.optDouble("addendum", 1.0),
            dedendumCoef = o.optDouble("dedendum", 1.25),
            hubDiameter = o.optDouble("hubDiameter", 10.0),
            hubLength = o.optDouble("hubLength", 0.0),   // audit H1: match the in-memory default
            coneAngleDeg = o.optDouble("coneAngle", 45.0),
            pitchConeDeg = o.optDouble("pitchCone", 45.0),
            mountingDistance = o.optDouble("mountingDistance", 25.0),
            pinionTeeth = o.optInt("pinionTeeth", 20),
            rackLength = o.optDouble("rackLength", 60.0),
            planetCount = o.optInt("planetCount", 3),
            planetTeeth = o.optInt("planetTeeth", 12),
            ringTeeth = o.optInt("ringTeeth", 44),
            wormStarts = o.optInt("wormStarts", 1),
            wheelTeeth = o.optInt("wheelTeeth", 30),
            material = o.optString("material", "Steel"),
            surfaceFinishUm = o.optDouble("surfaceFinish", 1.6),
            toleranceClass = o.optString("toleranceClass", "ISO 7"),
            lubrication = o.optString("lubrication", "Grease"),
            loadNm = o.optDouble("load", 10.0),
            speedRpm = o.optDouble("speed", 500.0),
            lifetimeHours = o.optDouble("lifetime", 10000.0),
            safetyFactor = o.optDouble("safetyFactor", 1.5),
            hubLeftLength = o.optDouble("hubLeftLength", 0.0),
            hubRightLength = o.optDouble("hubRightLength", 0.0),
            hubChamfer = o.optDouble("hubChamfer", 0.0),
            hubFillet = o.optDouble("hubFillet", 0.0),
            hubDraftAngleDeg = o.optDouble("hubDraftAngle", 0.0),
            setScrewCount = o.optInt("setScrewCount", 0),
            setScrewThread = o.optString("setScrewThread", "M3"),
            setScrewAngleDeg = o.optDouble("setScrewAngle", 90.0),
            setScrewAngle2Deg = o.optDouble("setScrewAngle2", 270.0),
            setScrewDepth = o.optDouble("setScrewDepth", 0.0),
            setScrewAxialOffset = o.optDouble("setScrewAxialOffset", 0.0),
            rootFilletCoef = o.optDouble("rootFilletCoef", 0.38),
            transitionCoef = o.optDouble("transitionCoef", 0.15),
            tipChamfer = o.optDouble("tipChamfer", 0.0),
            tipRelief = o.optDouble("tipRelief", 0.0),
            rootRelief = o.optDouble("rootRelief", 0.0),
            lighteningHoleCount = o.optInt("lighteningHoleCount", 0),
            lighteningHoleDiameter = o.optDouble("lighteningHoleDiameter", 0.0),
            lighteningHolePCD = o.optDouble("lighteningHolePCD", 0.0),
            spokeCount = o.optInt("spokeCount", 0),
            spokeWidth = o.optDouble("spokeWidth", 0.0),
            indexMarkType = o.optString("indexMarkType", "None"),
            indexMarkAngleDeg = o.optDouble("indexMarkAngle", 0.0),
            boreHoleTolerance = o.optString("boreHoleTolerance", "H7"),
            keywayTolerance = o.optString("keywayTolerance", "JS9"),
            beltProfile = o.optString("beltProfile", "GT2"),
            beltWidthMm = o.optDouble("beltWidth", 6.0),
            beltDriverTeeth = o.optInt("beltDriverTeeth", 20),
            beltDrivenTeeth = o.optInt("beltDrivenTeeth", 40),
            beltCenterDistanceMm = o.optDouble("beltCenterDistance", 0.0),
            beltTensionN = o.optDouble("beltTension", 50.0),
            beltBacklashMm = o.optDouble("beltBacklash", 0.0),
            beltFlangeCount = o.optInt("beltFlangeCount", 2),
            beltIdlerCount = o.optInt("beltIdlerCount", 0),
            toothOverrides = readToothOverrides(o.optJSONObject("toothOverrides"))
        ).coerced() // audit C6: cap deserialized counts/dimensions before geometry
    } catch (e: Exception) {
        android.util.Log.w("SavedConfigs", "Failed to parse saved config; dropping it", e) // audit H11
        null
    }

    private fun readToothOverrides(o: org.json.JSONObject?): Map<Int, com.gearforge.core.ToothOverride> {
        if (o == null) return emptyMap()
        val out = mutableMapOf<Int, com.gearforge.core.ToothOverride>()
        val keys = o.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val jo = o.optJSONObject(k) ?: continue
            out[k.toIntOrNull() ?: continue] = com.gearforge.core.ToothOverride(
                leftPressureAngleDeg = if (jo.has("la")) jo.getDouble("la") else null,
                rightPressureAngleDeg = if (jo.has("ra")) jo.getDouble("ra") else null,
                toothThickness = if (jo.has("t")) jo.getDouble("t") else null,
                addendumCoef = if (jo.has("a")) jo.getDouble("a") else null,
                dedendumCoef = if (jo.has("d")) jo.getDouble("d") else null,
                rootFilletCoef = if (jo.has("rf")) jo.getDouble("rf") else null,
                tipChamfer = if (jo.has("tc")) jo.getDouble("tc") else null,
                tipRelief = if (jo.has("tr")) jo.getDouble("tr") else null,
                rootRelief = if (jo.has("rr")) jo.getDouble("rr") else null,
                transitionCoef = if (jo.has("tn")) jo.getDouble("tn") else null
            )
        }
        return out
    }

    fun save(context: Context, name: String, params: GearParams) {
        val p = prefs(context)
        val map = runCatching { JSONObject(p.getString(MAP_KEY, "{}") ?: "{}") }.getOrElse { JSONObject() }
        map.put(name, toJson(params))
        p.edit().putString(MAP_KEY, map.toString()).apply()
    }

    fun list(context: Context): List<Pair<String, GearParams>> {
        val p = prefs(context)
        val map = runCatching { JSONObject(p.getString(MAP_KEY, "{}") ?: "{}") }.getOrElse { return emptyList() }
        val out = mutableListOf<Pair<String, GearParams>>()
        val keys = map.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            fromJson(map.optString(k))?.let { out.add(k to it) }
        }
        return out
    }

    fun delete(context: Context, name: String) {
        val p = prefs(context)
        val map = runCatching { JSONObject(p.getString(MAP_KEY, "{}") ?: "{}") }.getOrElse { return }
        map.remove(name)
        p.edit().putString(MAP_KEY, map.toString()).apply()
    }
}
