package com.gearforge.core

import java.util.Locale

/**
 * Minimal IGES 5.3 export of a triangle mesh. Each triangle is written as a 3-point
 * linear path (entity 106, form 1), producing a valid ASCII IGES file with the standard
 * Start/Global/Directory/Parameter/Terminate sections. Suitable as a neutral exchange
 * underlay; use STEP for a faceted solid.
 */
object IgesWriter {

    private const val WIDTH = 72

    fun write(mesh: Mesh): String {
        val sb = StringBuilder(65536)
        val entities = ArrayList<String>()  // parameter-data records

        // One 106 entity per triangle (4 points: a, b, c, a).
        val dirEntries = ArrayList<Pair<Int, String>>()
        for (t in mesh.triangles) {
            val a = mesh.vertices[t[0]]
            val b = mesh.vertices[t[1]]
            val c = mesh.vertices[t[2]]
            val pts = doubleArrayOf(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, a.x, a.y, a.z)
            entities.add(buildString {
                append("106,1,").append(4).append(',')
                for (i in pts.indices) {
                    if (i > 0) append(',')
                    append(f(pts[i]))
                }
                append(';')
            })
        }

        // ---- Start section ----
        sb.append("Gear Forge IGES export".padEnd(WIDTH)).append("S      1\n")

        // ---- Global section ----
        val globals = listOf(
            "1H,,1H;,7Hgear.igs,4HIGES,0.1,3.2768,13,0.002,300.0,",
            "1.0,1.0,8HGearForge,0.0,0.0254,8HGearForge,8H1.0,"
        )
        globals.forEachIndexed { i, g ->
            sb.append(g.padEnd(WIDTH)).append("G      ").append(i + 1).append('\n')
        }

        // ---- Directory entries ----
        var pdStart = 1 // 1-based line index of first parameter-data line
        val dirLines = ArrayList<String>()
        // compute parameter data line count (each entity is one line here)
        // We'll lay out directory first, then parameter data; compute pdStart after directory.
        // Simpler: build directory strings with placeholder pd pointers, then fix up.
        val pdPointers = IntArray(entities.size)
        // total directory lines = 2 * entities.size; parameter data starts after that + global/start lines.
        // We use a running line counter.
        var line = 1 + 1 + globals.size // start(1) + global lines
        line += 2 * entities.size        // directory entries
        for (i in entities.indices) {
            pdPointers[i] = line
            line += 1 // each 106 is one line (assuming short)
        }
        // Recompute properly below by emitting directory then parameter data with known layout.

        sb.setLength(0)
        // Rebuild with a two-pass line index.
        sb.append("Gear Forge IGES export".padEnd(WIDTH)).append("S      1\n")
        globals.forEachIndexed { i, g ->
            sb.append(g.padEnd(WIDTH)).append("G      ").append(i + 1).append('\n')
        }
        val dStart = 1 + 1 + globals.size + 1 // first directory line number (1-based)
        val pStart = dStart + 2 * entities.size
        for (i in entities.indices) {
            val de1 = buildString {
                append("     106").append("        ").append("1")  // entity type + param count
                append("        ").append("1")                        // form number
                append("        ").append("1")                        // structure 0
                append("        ").append("1")                        // line font
                append("        ").append("1")                        // level
                append("        ").append("1")                        // view
                append("        ").append("0")                        // transform
                append("        ").append("0")                        // label assoc
                append("        ").append("0")                        // status
            }.padEnd(WIDTH) + "D${dStart + 2 * i}"
            val de2 = buildString {
                append("     106").append("        ").append("0")     // entity type + 0
                append("        ").append("1")                        // line weight
                append("        ").append("1")                        // color
                append("        ").append(pStart + i)                 // parameter data pointer
                append("        ").append("0")                        // form
                append("        ").append("0")                        // reserved
                append("        ").append("0")                        // reserved
                append("        ").append("1")                        // entity label
                append("        ").append("0")                        // entity subscript
            }.padEnd(WIDTH) + "D${dStart + 2 * i + 1}"
            dirLines.add(de1)
            dirLines.add(de2)
        }
        dirLines.forEach { sb.append(it).append('\n') }

        // ---- Parameter data ----
        for (i in entities.indices) {
            sb.append(entities[i].padEnd(WIDTH)).append("P").append(pStart + i).append('\n')
        }

        // ---- Terminate ----
        val term = String.format(Locale.US, "S%7dG%7dD%7dP%7d", 1, globals.size, 2 * entities.size, entities.size)
        sb.append(term.padEnd(WIDTH)).append("T      1\n")
        return sb.toString()
    }

    private fun f(v: Double): String = String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
}
