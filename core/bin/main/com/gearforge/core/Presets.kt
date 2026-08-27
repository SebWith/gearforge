package com.gearforge.core

/**
 * Built-in library of recommended, industry-standard gear presets.
 *
 * Presets are grouped per gear type and each one carries a human-readable name,
 * description and a "typical use" hint in both English and Swedish, plus the full
 * [GearParams] it produces. Every preset is technically reasonable and representative
 * of how the gear type is used in practice (see docs/preset-library-design.md for the
 * rationale behind each value).
 *
 * All geometry is derived from [GearSpec.defaults] with type-appropriate overrides so
 * presets always stay in sync with the parameter registry and validation rules.
 */
object Presets {

    data class Preset(
        val id: String,
        val type: GearType,
        val nameEn: String,
        val nameSv: String,
        val descriptionEn: String,
        val descriptionSv: String,
        val useEn: String,
        val useSv: String,
        val params: GearParams
    )

    /** Presets for one gear type, in a stable presentation order. */
    fun forType(type: GearType): List<Preset> = ALL.filter { it.type == type }

    /** All presets across all gear types. */
    fun all(): List<Preset> = ALL

    fun byId(id: String): Preset? = ALL.firstOrNull { it.id == id }

    private fun preset(
        id: String,
        type: GearType,
        nameEn: String, nameSv: String,
        descriptionEn: String, descriptionSv: String,
        useEn: String, useSv: String,
        params: GearParams
    ) = Preset(id, type, nameEn, nameSv, descriptionEn, descriptionSv, useEn, useSv, params)

    private val ALL: List<Preset> = listOf(
        // ---- Spur gears --------------------------------------------------
        preset(
            "spur-fine", GearType.SPUR,
            "Fine precision", "Fin precision",
            "Module 0.5 with 20 teeth for compact, high-precision mechanisms.",
            "Modul 0,5 med 20 kuggar för kompakta mekanismer med hög precision.",
            "Instruments, small actuators", "Instrument, små ställdon",
            GearSpec.defaults(GearType.SPUR).copy(module = 0.5, teeth = 20)
        ),
        preset(
            "spur-general", GearType.SPUR,
            "General purpose", "Allmänt bruk",
            "The classic M1 · 20-tooth gear — a safe all-rounder.",
            "Den klassiska M1 · 20-kuggars växeln — ett säkert allroundval.",
            "Prototypes, general mechanics", "Prototyper, allmän mekanik",
            GearSpec.defaults(GearType.SPUR).copy(module = 1.0, teeth = 20)
        ),
        preset(
            "spur-heavy", GearType.SPUR,
            "Heavy / printable", "Grov / utskrivbar",
            "Strong module 2 teeth sized for easy 3D printing and higher torque.",
            "Grov modul 2 med kuggar anpassade för 3D-utskrift och högre vridmoment.",
            "Robots, drivelines", "Robotar, drivlinor",
            GearSpec.defaults(GearType.SPUR).copy(module = 2.0, teeth = 24, material = "PLA")
        ),

        // ---- Helical gears --------------------------------------------------
        preset(
            "helical-quiet", GearType.HELICAL,
            "Quiet mesh", "Tyst ingrepp",
            "15° helix angle for smooth, low-noise meshing.",
            "15° helixvinkel för mjukt ingrepp med lågt ljud.",
            "Printers, quiet drives", "Skrivare, tysta drivningar",
            GearSpec.defaults(GearType.HELICAL).copy(module = 1.0, teeth = 16, helixAngleDeg = 15.0)
        ),
        preset(
            "helical-torque", GearType.HELICAL,
            "High torque", "Högt vridmoment",
            "Module 2 with a 30° helix for heavy load carrying.",
            "Modul 2 med 30° helixvinkel för hög belastning.",
            "Transmissions", "Transmissioner",
            GearSpec.defaults(GearType.HELICAL).copy(module = 2.0, teeth = 20, helixAngleDeg = 30.0)
        ),
        preset(
            "helical-ratio", GearType.HELICAL,
            "Smooth ratio", "Mjuk utväxling",
            "30 teeth with a 20° helix for smooth higher ratios.",
            "30 kuggar med 20° helixvinkel för mjuka högre utväxlingar.",
            "Machine tools", "Verktygsmaskiner",
            GearSpec.defaults(GearType.HELICAL).copy(module = 1.0, teeth = 30, helixAngleDeg = 20.0)
        ),

        // ---- Bevel gears --------------------------------------------------
        preset(
            "bevel-miter", GearType.BEVEL,
            "Miter 1:1", "Miter 1:1",
            "45° cone pair for a 1:1 right-angle drive.",
            "45° konpar för en 1:1-drivning i rät vinkel.",
            "Right-angle drives", "Rätvinkliga drivningar",
            GearSpec.defaults(GearType.BEVEL).copy(module = 1.0, teeth = 20, coneAngleDeg = 45.0, pitchConeDeg = 45.0)
        ),
        preset(
            "bevel-steering", GearType.BEVEL,
            "Steering / compact", "Styrning / kompakt",
            "Module 1.5 bevel for compact intersecting shafts.",
            "Koniskt hjul modul 1,5 för kompakta korsande axlar.",
            "Steering, hand tools", "Styrning, handverktyg",
            GearSpec.defaults(GearType.BEVEL).copy(module = 1.5, teeth = 16, coneAngleDeg = 45.0, pitchConeDeg = 45.0)
        ),
        preset(
            "bevel-heavy", GearType.BEVEL,
            "High torque", "Högt vridmoment",
            "Module 2 bevel with a wider cone for stronger meshing.",
            "Koniskt hjul modul 2 med bredare kon för starkare ingrepp.",
            "Differentials", "Differentialer",
            GearSpec.defaults(GearType.BEVEL).copy(module = 2.0, teeth = 20, coneAngleDeg = 60.0, pitchConeDeg = 60.0)
        ),

        // ---- Rack & pinion --------------------------------------------------
        preset(
            "rack-precision", GearType.RACK,
            "Precision linear", "Precisionslinjär",
            "M1 rack with a 20-tooth pinion for smooth linear motion.",
            "M1-kuggstång med 20-kuggars pinjong för mjuk linjär rörelse.",
            "CNC, linear stages", "CNC, linjära bord",
            GearSpec.defaults(GearType.RACK).copy(module = 1.0, teeth = 10, pinionTeeth = 20, rackLength = 60.0)
        ),
        preset(
            "rack-cnc", GearType.RACK,
            "CNC axis", "CNC-axel",
            "Longer M1.5 rack for machine axis travel.",
            "Längre M1,5-kuggstång för maskinaxlar.",
            "Machine axes", "Maskinaxlar",
            GearSpec.defaults(GearType.RACK).copy(module = 1.5, teeth = 12, pinionTeeth = 20, rackLength = 100.0)
        ),
        preset(
            "rack-heavy", GearType.RACK,
            "Heavy linear", "Grov linjär",
            "Module 2 rack for high-force linear drives.",
            "Modul 2-kuggstång för linjära drivningar med hög kraft.",
            "Presses, actuators", "Pressar, ställdon",
            GearSpec.defaults(GearType.RACK).copy(module = 2.0, teeth = 10, pinionTeeth = 16, rackLength = 80.0)
        ),

        // ---- Planetary --------------------------------------------------
        preset(
            "planetary-3", GearType.PLANETARY,
            "3:1 reduction", "3:1 reduktion",
            "Sun 16, planets 8, ring 32 for a compact 3:1 stage.",
            "Sol 16, planeter 8, ring 32 för ett kompakt 3:1-steg.",
            "Gearmotors", "Växelmotorer",
            GearSpec.defaults(GearType.PLANETARY).copy(teeth = 16, planetTeeth = 8, ringTeeth = 32, planetCount = 3)
        ),
        preset(
            "planetary-5", GearType.PLANETARY,
            "5:1 reduction", "5:1 reduktion",
            "Sun 12, planets 18, ring 48 for a 5:1 stage.",
            "Sol 12, planeter 18, ring 48 för ett 5:1-steg.",
            "Robotics, actuators", "Robotik, ställdon",
            GearSpec.defaults(GearType.PLANETARY).copy(teeth = 12, planetTeeth = 18, ringTeeth = 48, planetCount = 3)
        ),
        preset(
            "planetary-7", GearType.PLANETARY,
            "7:1 reduction", "7:1 reduktion",
            "Sun 12, planets 30, ring 72 for a high 7:1 stage.",
            "Sol 12, planeter 30, ring 72 för ett högt 7:1-steg.",
            "High-torque reducers", "Högmoment-reducerare",
            GearSpec.defaults(GearType.PLANETARY).copy(teeth = 12, planetTeeth = 30, ringTeeth = 72, planetCount = 3)
        ),

        // ---- Worm pair --------------------------------------------------
        preset(
            "worm-30", GearType.WORM_PAIR,
            "30:1 reduction", "30:1 reduktion",
            "Single-start worm driving a 30-tooth wheel.",
            "Snäcka med en ingång som driver ett 30-kuggars hjul.",
            "Conveyors, hoists", "Transportörer, lyftar",
            GearSpec.defaults(GearType.WORM_PAIR).copy(wormStarts = 1, wheelTeeth = 30)
        ),
        preset(
            "worm-15", GearType.WORM_PAIR,
            "15:1 reduction", "15:1 reduktion",
            "Two-start worm for a faster 15:1 drive.",
            "Snäcka med två ingångar för en snabbare 15:1-drivning.",
            "Indexers, feeders", "Indexerare, matare",
            GearSpec.defaults(GearType.WORM_PAIR).copy(wormStarts = 2, wheelTeeth = 30)
        ),
        preset(
            "worm-40", GearType.WORM_PAIR,
            "40:1 self-locking", "40:1 självhämmande",
            "Single-start worm with 40 teeth — typically self-locking.",
            "Snäcka med en ingång och 40 kuggar — vanligen självhämmande.",
            "Lifts, positioning", "Lyftar, positionering",
            GearSpec.defaults(GearType.WORM_PAIR).copy(wormStarts = 1, wheelTeeth = 40)
        ),

        // ---- Internal ring --------------------------------------------------
        preset(
            "ring-planetary", GearType.INTERNAL_RING,
            "Planetary ring", "Planetring",
            "M1 · 44-tooth ring that matches the standard planetary set.",
            "M1 · 44-kuggars ring som matchar standardplanetväxeln.",
            "Planetary outer ring", "Planetväxelns ytterring",
            GearSpec.defaults(GearType.INTERNAL_RING).copy(module = 1.0, teeth = 44)
        ),
        preset(
            "ring-compact", GearType.INTERNAL_RING,
            "Compact ring", "Kompakt ring",
            "M1 · 36 teeth for tighter assemblies.",
            "M1 · 36 kuggar för tätare sammansättningar.",
            "Compact reducers", "Kompakta reducerare",
            GearSpec.defaults(GearType.INTERNAL_RING).copy(module = 1.0, teeth = 36)
        ),
        preset(
            "ring-large", GearType.INTERNAL_RING,
            "Large ring", "Stor ring",
            "M1.5 · 60 teeth for larger, stronger rings.",
            "M1,5 · 60 kuggar för större, starkare ringar.",
            "Heavy reducers", "Tunga reducerare",
            GearSpec.defaults(GearType.INTERNAL_RING).copy(module = 1.5, teeth = 60)
        ),

        // ---- Hypoid --------------------------------------------------
        preset(
            "hypoid-auto", GearType.HYPOID,
            "Automotive axle", "Fordonsaxel",
            "Module 1.5 hypoid with 45° cone for offset axles.",
            "Hypoidhjul modul 1,5 med 45° kon för förskjutna axlar.",
            "Automotive axles", "Fordonsaxlar",
            GearSpec.defaults(GearType.HYPOID).copy(module = 1.5, teeth = 20, coneAngleDeg = 45.0, pitchConeDeg = 45.0)
        ),
        preset(
            "hypoid-compact", GearType.HYPOID,
            "Compact", "Kompakt",
            "M1 hypoid with a 35° cone for tight packages.",
            "M1-hypoidhjul med 35° kon för kompakta konstruktioner.",
            "Power tools", "Elverktyg",
            GearSpec.defaults(GearType.HYPOID).copy(module = 1.0, teeth = 20, coneAngleDeg = 35.0, pitchConeDeg = 35.0)
        ),
        preset(
            "hypoid-heavy", GearType.HYPOID,
            "Heavy duty", "Tung drift",
            "Module 2 hypoid for high torque transfer.",
            "Hypoidhjul modul 2 för hög vridmomentöverföring.",
            "Trucks, machinery", "Lastbilar, maskiner",
            GearSpec.defaults(GearType.HYPOID).copy(module = 2.0, teeth = 16, coneAngleDeg = 40.0, pitchConeDeg = 40.0)
        ),

        // ---- Cycloidal --------------------------------------------------
        preset(
            "cyclo-clock", GearType.CYCLOIDAL,
            "Clockwork", "Urverk",
            "Cycloidal teeth for smooth clock and instrument drives.",
            "Cykloida kuggar för mjuka drivningar i ur och instrument.",
            "Clocks, instruments", "Ur, instrument",
            GearSpec.defaults(GearType.CYCLOIDAL).copy(module = 0.5, teeth = 20, toothProfile = ToothProfile.CYCLOID)
        ),
        preset(
            "cyclo-reducer", GearType.CYCLOIDAL,
            "Precision reducer", "Precisionsreducerare",
            "M1 cycloidal disc for precision reducers.",
            "M1-cykloidskiva för precisionsreducerare.",
            "Robot joints", "Robotleder",
            GearSpec.defaults(GearType.CYCLOIDAL).copy(module = 1.0, teeth = 12, toothProfile = ToothProfile.CYCLOID)
        ),
        preset(
            "cyclo-fine", GearType.CYCLOIDAL,
            "Fine cycloid", "Fin cykloid",
            "Module 0.5 with 30 teeth for very fine drives.",
            "Modul 0,5 med 30 kuggar för mycket fina drivningar.",
            "Fine mechanisms", "Finmekanik",
            GearSpec.defaults(GearType.CYCLOIDAL).copy(module = 0.5, teeth = 30, toothProfile = ToothProfile.CYCLOID)
        ),

        // ---- Harmonic drive --------------------------------------------------
        preset(
            "harmonic-high", GearType.HARMONIC_DRIVE,
            "High ratio", "Hög utväxling",
            "160-tooth flexspline for a high reduction stage.",
            "160-kuggars flexspline för ett högt reduktionssteg.",
            "Robot joints", "Robotleder",
            GearSpec.defaults(GearType.HARMONIC_DRIVE).copy(module = 0.5, teeth = 160, toothProfile = ToothProfile.CYCLOID)
        ),
        preset(
            "harmonic-ultra", GearType.HARMONIC_DRIVE,
            "Ultra-precision", "Ultraprecision",
            "200-tooth fine flexspline for the highest precision.",
            "200-kuggars fin flexspline för högsta precision.",
            "Precision stages", "Precisionsbord",
            GearSpec.defaults(GearType.HARMONIC_DRIVE).copy(module = 0.3, teeth = 200, toothProfile = ToothProfile.CYCLOID)
        ),
        preset(
            "harmonic-compact", GearType.HARMONIC_DRIVE,
            "Compact", "Kompakt",
            "120-tooth flexspline in a smaller module.",
            "120-kuggars flexspline med mindre modul.",
            "Compact actuators", "Kompakta ställdon",
            GearSpec.defaults(GearType.HARMONIC_DRIVE).copy(module = 1.0, teeth = 120, toothProfile = ToothProfile.CYCLOID)
        ),

        // ---- Face gear --------------------------------------------------
        preset(
            "face-standard", GearType.FACE_GEAR,
            "Standard face", "Standard planväxel",
            "40-tooth face gear meshing with a pinion.",
            "40-kuggars plankugghjul som ingriper med en pinjong.",
            "Right-angle drives", "Rätvinkliga drivningar",
            GearSpec.defaults(GearType.FACE_GEAR).copy(module = 1.0, teeth = 40)
        ),
        preset(
            "face-compact", GearType.FACE_GEAR,
            "Compact face", "Kompakt planväxel",
            "30-tooth face gear for tight spaces.",
            "30-kuggars plankugghjul för trånga utrymmen.",
            "Compact drives", "Kompakta drivningar",
            GearSpec.defaults(GearType.FACE_GEAR).copy(module = 1.0, teeth = 30)
        ),
        preset(
            "face-heavy", GearType.FACE_GEAR,
            "Heavy face", "Grov planväxel",
            "Module 1.5 face gear for stronger meshes.",
            "Plankugghjul modul 1,5 för starkare ingrepp.",
            "Power tools", "Elverktyg",
            GearSpec.defaults(GearType.FACE_GEAR).copy(module = 1.5, teeth = 40)
        ),

        // ---- Screw gear --------------------------------------------------
        preset(
            "screw-1to1", GearType.SCREW_GEAR,
            "1:1 crossed", "1:1 korsad",
            "45° helix pair for a crossed 1:1 drive.",
            "45° helixpar för en korsad 1:1-drivning.",
            "Crossed shafts", "Korsade axlar",
            GearSpec.defaults(GearType.SCREW_GEAR).copy(module = 1.0, teeth = 20, helixAngleDeg = 45.0)
        ),
        preset(
            "screw-2to1", GearType.SCREW_GEAR,
            "2:1 crossed", "2:1 korsad",
            "30° helix for a crossed 2:1 drive.",
            "30° helixvinkel för en korsad 2:1-drivning.",
            "Skew drives", "Skeva drivningar",
            GearSpec.defaults(GearType.SCREW_GEAR).copy(module = 1.0, teeth = 20, helixAngleDeg = 30.0)
        ),
        preset(
            "screw-heavy", GearType.SCREW_GEAR,
            "Heavy crossed", "Grov korsad",
            "Module 1.5 crossed pair for higher loads.",
            "Korsat par modul 1,5 för högre belastning.",
            "Heavy skew drives", "Tunga skeva drivningar",
            GearSpec.defaults(GearType.SCREW_GEAR).copy(module = 1.5, teeth = 20, helixAngleDeg = 45.0)
        ),

        // ---- Timing belt --------------------------------------------------
        preset(
            "belt-gt2-20-40", GearType.BELT,
            "GT2 20:40", "GT2 20:40",
            "GT2 belt with 20-tooth driver and 40-tooth driven pulley — a 2:1 reduction.",
            "GT2-rem med 20-kuggars drivhjul och 40-kuggars drivet hjul — 2:1 utväxling.",
            "3D printers, light axes", "3D-skrivare, lätta axlar",
            GearSpec.defaults(GearType.BELT).copy(
                beltProfile = "GT2", beltDriverTeeth = 20, beltDrivenTeeth = 40, beltWidthMm = 6.0
            )
        ),
        preset(
            "belt-gt2-16-80", GearType.BELT,
            "GT2 16:80", "GT2 16:80",
            "GT2 belt with a 16-tooth driver and 80-tooth driven pulley — a 5:1 reduction.",
            "GT2-rem med 16-kuggars drivhjul och 80-kuggars drivet hjul — 5:1 utväxling.",
            "Precision axes, pan/tilt", "Precisionsaxlar, pan/tilt",
            GearSpec.defaults(GearType.BELT).copy(
                beltProfile = "GT2", beltDriverTeeth = 16, beltDrivenTeeth = 80, beltWidthMm = 6.0
            )
        ),
        preset(
            "belt-htd5m-20-40", GearType.BELT,
            "HTD 5M 20:40", "HTD 5M 20:40",
            "Heavier HTD 5M belt for a robust 2:1 power transmission.",
            "Grovare HTD 5M-rem för robust 2:1 kraftöverföring.",
            "Conveyors, power drives", "Transportörer, kraftdrivningar",
            GearSpec.defaults(GearType.BELT).copy(
                beltProfile = "HTD 5M", beltDriverTeeth = 20, beltDrivenTeeth = 40, beltWidthMm = 15.0
            )
        )
    )
}
