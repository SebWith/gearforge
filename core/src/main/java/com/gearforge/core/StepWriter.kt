package com.gearforge.core

import java.util.Locale

/**
 * ASCII STEP (ISO 10303-21) export of a mesh as a faceted BREP
 * (AP214/AP203 AUTOMOTIVE_DESIGN schema). Every triangle becomes an ADVANCED_FACE
 * with a PLANE surface, shared vertices and shared edges, producing a valid closed shell.
 */
object StepWriter {

    fun write(mesh: Mesh): String {
        val sb = StringBuilder(65536)
        var id = 0
        fun nxt(): Int = ++id

        sb.append("ISO-10303-21;\n")
        sb.append("HEADER;\n")
        sb.append("FILE_DESCRIPTION(('Gear Forge mesh'),'2;1');\n")
        sb.append("FILE_NAME('gear.step','',(''),(''),'','Gear Forge','');\n")
        sb.append("FILE_SCHEMA(('AUTOMOTIVE_DESIGN { 1 0 10303 214 1 1 1 1 }'));\n")
        sb.append("ENDSEC;\nDATA;\n")

        // --- context chain ---
        val ctx = nxt()
        sb.append("#$ctx=APPLICATION_CONTEXT('automotive design');\n")
        val ap = nxt()
        sb.append("#$ap=APPLICATION_PROTOCOL_DEFINITION('international standard','automotive_design',2003,#$ctx);\n")
        val product = nxt()
        sb.append("#$product=PRODUCT('Gear','Gear','',(#$ctx));\n")
        val pdf = nxt()
        sb.append("#$pdf=PRODUCT_DEFINITION_FORMATION('','',#$product);\n")
        val pdc = nxt()
        sb.append("#$pdc=PRODUCT_DEFINITION_CONTEXT('',#$ctx,'design');\n")
        val pd = nxt()
        sb.append("#$pd=PRODUCT_DEFINITION('','',#$pdf,#$pdc);\n")
        val lenUnit = nxt()
        sb.append("#$lenUnit=(LENGTH_UNIT()NAMED_UNIT(*)SI_UNIT(.MILLI.,.METRE.));\n")
        val angUnit = nxt()
        sb.append("#$angUnit=(NAMED_UNIT(*)PLANE_ANGLE_UNIT()SI_UNIT(\u0024,.RADIAN.));\n")
        val solUnit = nxt()
        sb.append("#$solUnit=(NAMED_UNIT(*)SI_UNIT(\u0024,.STERADIAN.)SOLID_ANGLE_UNIT());\n")
        val uncertain = nxt()
        sb.append("#$uncertain=UNCERTAINTY_MEASURE_WITH_UNIT(LENGTH_MEASURE(1.0E-6),#$lenUnit,'distance_accuracy_value','confusion accuracy');\n")
        val geo = nxt()
        sb.append("#$geo=(GEOMETRIC_REPRESENTATION_CONTEXT(3)GLOBAL_UNCERTAINTY_ASSIGNED_CONTEXT((#$uncertain))GLOBAL_UNIT_ASSIGNED_CONTEXT((#$lenUnit,#$angUnit,#$solUnit))REPRESENTATION_CONTEXT('Context','3D Context with UNIT and UNCERTAINTY'));\n")

        // --- vertices ---
        val pointIds = IntArray(mesh.vertices.size)
        val vertexIds = IntArray(mesh.vertices.size)
        for (i in mesh.vertices.indices) {
            val v = mesh.vertices[i]
            val p = nxt(); pointIds[i] = p
            sb.append("#$p=CARTESIAN_POINT('',(${f(v.x)},${f(v.y)},${f(v.z)}));\n")
            val vp = nxt(); vertexIds[i] = vp
            sb.append("#$vp=VERTEX_POINT('',#$p);\n")
        }

        // --- shared edges ---
        val edgeMap = LinkedHashMap<Long, Int>()
        fun key(a: Int, b: Int): Long {
            val lo = minOf(a, b).toLong()
            val hi = maxOf(a, b).toLong()
            return (lo shl 32) or hi
        }
        val triEdges = ArrayList<IntArray>(mesh.triangles.size)
        for (t in mesh.triangles) {
            val edges = IntArray(3)
            for (e in 0 until 3) {
                val a = t[e]; val b = t[(e + 1) % 3]
                val k = key(a, b)
                val ex = edgeMap[k]
                if (ex != null) { edges[e] = ex; continue }
                val va = mesh.vertices[a]; val vb = mesh.vertices[b]
                val dir = nxt()
                sb.append("#$dir=VECTOR('',${f(vb.x - va.x)},${f(vb.y - va.y)},${f(vb.z - va.z)});\n")
                val line = nxt()
                sb.append("#$line=LINE('',#$pointIds[a],#$dir);\n")
                val ec = nxt()
                sb.append("#$ec=EDGE_CURVE('',#$vertexIds[a],#$vertexIds[b],#$line,.T.);\n")
                edgeMap[k] = ec
                edges[e] = ec
            }
            triEdges.add(edges)
        }

        // --- faces ---
        val faceIds = ArrayList<Int>(mesh.triangles.size)
        for (ti in mesh.triangles.indices) {
            val t = mesh.triangles[ti]
            val nrm = MeshOps.faceNormal(mesh.vertices[t[0]], mesh.vertices[t[1]], mesh.vertices[t[2]])
            val oe = IntArray(3)
            for (e in 0 until 3) {
                val o = nxt(); oe[e] = o
                sb.append("#$o=ORIENTED_EDGE('',*,*,#${triEdges[ti][e]},.T.);\n")
            }
            val loop = nxt()
            sb.append("#$loop=EDGE_LOOP('',(#${oe[0]},#${oe[1]},#${oe[2]}));\n")
            val bound = nxt()
            sb.append("#$bound=FACE_OUTER_BOUND('',#$loop,.T.);\n")
            val dirN = nxt()
            sb.append("#$dirN=DIRECTION('',${f(nrm.x)},${f(nrm.y)},${f(nrm.z)});\n")
            val dirR = nxt()
            sb.append("#$dirR=DIRECTION('',1.0,0.0,0.0);\n")
            val ax2 = nxt()
            sb.append("#$ax2=AXIS2_PLACEMENT_3D('',#$pointIds[t[0]],#$dirN,#$dirR);\n")
            val plane = nxt()
            sb.append("#$plane=PLANE('',#$ax2);\n")
            val face = nxt(); faceIds.add(face)
            sb.append("#$face=ADVANCED_FACE('',(#$bound),#$plane,.T.);\n")
        }

        val shell = nxt()
        sb.append("#$shell=CLOSED_SHELL('',(${faceIds.joinToString(",") { "#$it" }}));\n")
        val solid = nxt()
        sb.append("#$solid=MANIFOLD_SOLID_BREP('',#$shell);\n")
        val brep = nxt()
        sb.append("#$brep=ADVANCED_BREP_SHAPE_REPRESENTATION('',(#$solid),#$geo);\n")
        val sr = nxt()
        sb.append("#$sr=SHAPE_REPRESENTATION_RELATIONSHIP('','',#$brep,#$pd);\n")
        sb.append("ENDSEC;\nEND-ISO-10303-21;\n")
        return sb.toString()
    }

    private fun f(v: Double): String = String.format(Locale.US, "%.8f", v).trimEnd('0').trimEnd('.')
}
