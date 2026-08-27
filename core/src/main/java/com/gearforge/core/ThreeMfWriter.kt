package com.gearforge.core

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal valid 3MF (OPC package) export. */
object ThreeMfWriter {

    private const val CONTENT_TYPES =
        """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
</Types>"""

    private const val RELS =
        """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>"""

    fun write(mesh: Mesh): ByteArray {
        val model = modelXml(mesh)
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELS.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(model.toByteArray(StandardCharsets.UTF_8))
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun modelXml(mesh: Mesh): String {
        val sb = StringBuilder(8192)
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<model unit=\"millimeter\" xml:lang=\"en-US\" xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\">\n")
        sb.append("  <resources>\n    <object id=\"1\" type=\"model\">\n      <mesh>\n        <vertices>\n")
        for (v in mesh.vertices) {
            sb.append("          <vertex x=\"").append(fmt(v.x))
              .append("\" y=\"").append(fmt(v.y))
              .append("\" z=\"").append(fmt(v.z)).append("\"/>\n")
        }
        sb.append("        </vertices>\n        <triangles>\n")
        for (t in mesh.triangles) {
            sb.append("          <triangle v1=\"").append(t[0])
              .append("\" v2=\"").append(t[1])
              .append("\" v3=\"").append(t[2]).append("\"/>\n")
        }
        sb.append("        </triangles>\n      </mesh>\n    </object>\n  </resources>\n")
        sb.append("  <build>\n    <item objectid=\"1\"/>\n  </build>\n</model>\n")
        return sb.toString()
    }

    private fun fmt(v: Double): String = String.format(Locale.US, "%.6f", v)
}
