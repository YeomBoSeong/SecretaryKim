package com.ybs.assistant.data

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * write_file이 .docx/.xlsx를 저장할 때 쓰는 최소 OOXML 생성기.
 * AI가 넘긴 평문(content)을 그대로 담을 뿐 기존 파일의 서식/스타일은 보존하지 않는다 —
 * docx는 줄바꿈마다 새 문단, xlsx는 줄바꿈마다 새 행 · 탭(\t)마다 새 셀이 된다.
 * (read_file이 xlsx를 탭으로 이어붙인 텍스트로 돌려주는 것과 대칭되는 규칙이라 왕복 편집이 가능하다.)
 */
object DocumentBuilder {

    fun buildDocx(content: String): ByteArray {
        val paragraphs = content.split("\n").joinToString("") { line ->
            "<w:p><w:r><w:t xml:space=\"preserve\">${escapeXml(line)}</w:t></w:r></w:p>"
        }
        val documentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>$paragraphs<w:sectPr/></w:body></w:document>"""

        return zip(
            "[Content_Types].xml" to CONTENT_TYPES_DOCX,
            "_rels/.rels" to relsRoot("word/document.xml"),
            "word/document.xml" to documentXml,
        )
    }

    fun buildXlsx(content: String): ByteArray {
        val rowsXml = content.split("\n").mapIndexed { rowIndex, line ->
            val cellsXml = line.split("\t").mapIndexed { colIndex, cell ->
                val ref = "${columnLetter(colIndex)}${rowIndex + 1}"
                """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">${escapeXml(cell)}</t></is></c>"""
            }.joinToString("")
            """<row r="${rowIndex + 1}">$cellsXml</row>"""
        }.joinToString("")

        val sheetXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>$rowsXml</sheetData></worksheet>"""

        return zip(
            "[Content_Types].xml" to CONTENT_TYPES_XLSX,
            "_rels/.rels" to relsRoot("xl/workbook.xml"),
            "xl/workbook.xml" to WORKBOOK_XML,
            "xl/_rels/workbook.xml.rels" to WORKBOOK_RELS,
            "xl/worksheets/sheet1.xml" to sheetXml,
        )
    }

    /** 0→A, 25→Z, 26→AA ... 엑셀 열 문자로 변환 */
    private fun columnLetter(index: Int): String {
        var i = index
        val sb = StringBuilder()
        do {
            sb.insert(0, 'A' + (i % 26))
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    private fun escapeXml(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, text) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(text.toByteArray(Charsets.UTF_8))
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun relsRoot(target: String) = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="$target"/></Relationships>"""

    private const val CONTENT_TYPES_DOCX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/></Types>"""

    private const val CONTENT_TYPES_XLSX = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>"""

    private const val WORKBOOK_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Sheet1" sheetId="1" r:id="rId1"/></sheets></workbook>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>"""
}
