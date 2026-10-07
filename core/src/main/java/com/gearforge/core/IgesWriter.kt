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
    private const val SEQ_WIDTH = 7

    /**
     * Appends one IGES record: 72 data columns, the section letter in column 73, and the
     * sequence number right-justified in columns 74-80.
     *
     * The 80-column length is not cosmetic. `padEnd(WIDTH)` does not truncate, so any data
     * longer than 72 characters used to produce an overlong record - a file CAD programs
     * reject without a readable error. Measured before the fix: 16 834 records longer than
     * 80 columns in an exported gear.
     */
    private fun record(sb: StringBuilder, data: String, section: Char, seq: Int) {
        require(data.length <= WIDTH) { "IGES record data is ${data.length} columns, max is $WIDTH" }
        sb.append(data.padEnd(WIDTH))
        sb.append(section)
        sb.append(String.format(Locale.US, "%${SEQ_WIDTH}d", seq))
        sb.append('\n')
    }

    /** One directory-entry field: 8 columns, right-justified. Nine of them make one record. */
    private fun deField(value: Int): String = String.format(Locale.US, "%8d", value)

    /**
     * Folds free-form parameter data into 72-column records. IGES continues a parameter record
     * across as many records as it needs - the Directory Entry stores the sequence number of the
     * first one and the count. Breaks after a comma where possible so the split never lands
     * inside a number.
     */
    private fun foldParameters(data: String): List<String> {
        if (data.length <= WIDTH) return listOf(data)
        val out = ArrayList<String>()
        var rest = data
        while (rest.length > WIDTH) {
            var cut = rest.lastIndexOf(',', WIDTH - 1)
            if (cut <= 0) cut = WIDTH - 1
            out.add(rest.substring(0, cut + 1))
            rest = rest.substring(cut + 1)
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    fun write(mesh: Mesh): String {
        val sb = StringBuilder(65536)

        // One 106 entity per triangle (4 points: a, b, c, a), each folded to 72-column records.
        val paramRecords = ArrayList<List<String>>(mesh.triangles.size)
        for (t in mesh.triangles) {
            val a = mesh.vertices[t[0]]
            val b = mesh.vertices[t[1]]
            val c = mesh.vertices[t[2]]
            val pts = doubleArrayOf(a.x, a.y, a.z, b.x, b.y, b.z, c.x, c.y, c.z, a.x, a.y, a.z)
            val data = buildString {
                append("106,1,").append(4).append(',')
                for (i in pts.indices) {
                    if (i > 0) append(',')
                    append(f(pts[i]))
                }
                append(';')
            }
            paramRecords.add(foldParameters(data))
        }

        val globals = listOf(
            "1H,,1H;,7Hgear.igs,4HIGES,0.1,3.2768,13,0.002,300.0,",
            "1.0,1.0,8HGearForge,0.0,0.0254,8HGearForge,8H1.0,"
        )

        val entityCount = paramRecords.size
        val dirRecordCount = 2 * entityCount
        val paramRecordCount = paramRecords.sumOf { it.size }

        // ---- Start section ----
        record(sb, "GearForge IGES export", 'S', 1)

        // ---- Global section ----
        globals.forEachIndexed { i, g -> record(sb, g, 'G', i + 1) }

        // Sequence numbers run contiguously through the parameter section; every entity
        // remembers the number of its first record for the Directory Entry pointer.
        val pdFirst = IntArray(entityCount)
        var pdSeq = 1
        for (i in 0 until entityCount) {
            pdFirst[i] = pdSeq
            pdSeq += paramRecords[i].size
        }

        // ---- Directory entries: odd record, then even record, numbered 1..2n ----
        for (i in 0 until entityCount) {
            val odd = buildString {
                append(deField(106))              // entity type
                append(deField(pdFirst[i]))       // parameter data pointer
                append(deField(1))                // structure
                append(deField(1))                // line font
                append(deField(1))                // level
                append(deField(1))                // view
                append(deField(0))                // transformation matrix = none
                append(deField(0))                // label display associativity
                append(deField(0))                // status number
            }
            record(sb, odd, 'D', 2 * i + 1)
            val even = buildString {
                append(deField(106))                   // entity type
                append(deField(1))                     // line weight
                append(deField(1))                     // colour
                append(deField(paramRecords[i].size))  // parameter LINE COUNT, not a pointer
                append(deField(1))                     // form number
                append(deField(0))                     // reserved
                append(deField(0))                     // reserved
                append(deField(1))                     // entity label
                append(deField(0))                     // entity subscript
            }
            record(sb, even, 'D', 2 * i + 2)
        }

        // ---- Parameter data ----
        var p = 1
        for (i in 0 until entityCount) {
            for (r in paramRecords[i]) {
                record(sb, r, 'P', p)
                p++
            }
        }

        // ---- Terminate: the record count of each section ----
        val term = String.format(
            Locale.US,
            "S%7dG%7dD%7dP%7d",
            1, globals.size, dirRecordCount, paramRecordCount
        )
        record(sb, term, 'T', 1)

        return sb.toString()
    }

    private fun f(v: Double): String = String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
}
