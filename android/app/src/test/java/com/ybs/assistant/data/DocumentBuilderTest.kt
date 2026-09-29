package com.ybs.assistant.data

import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentBuilderTest {

    private fun zipEntries(bytes: ByteArray): Map<String, String> {
        val result = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                result[entry.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return result
    }

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `buildDocx creates one paragraph per line and escapes special chars`() {
        val bytes = DocumentBuilder.buildDocx("첫 줄\n둘째 줄 & <특수문자>")
        val entries = zipEntries(bytes)

        assertTrue(entries.containsKey("[Content_Types].xml"))
        assertTrue(entries.containsKey("_rels/.rels"))
        assertTrue(entries.containsKey("word/document.xml"))

        val doc = parseXml(entries.getValue("word/document.xml"))
        val texts = doc.getElementsByTagNameNS("*", "t")
        assertEquals(2, texts.length)
        assertEquals("첫 줄", texts.item(0).textContent)
        assertEquals("둘째 줄 & <특수문자>", texts.item(1).textContent)
    }

    @Test
    fun `buildDocx produces well-formed content types and rels pointing at document xml`() {
        val entries = zipEntries(DocumentBuilder.buildDocx("본문"))
        parseXml(entries.getValue("[Content_Types].xml"))
        val rels = parseXml(entries.getValue("_rels/.rels"))
        val target = rels.getElementsByTagName("Relationship").item(0).attributes.getNamedItem("Target").textContent
        assertEquals("word/document.xml", target)
    }

    @Test
    fun `buildXlsx creates one row per line and one cell per tab`() {
        val bytes = DocumentBuilder.buildXlsx("이름\t나이\n홍길동\t30")
        val entries = zipEntries(bytes)

        assertTrue(entries.containsKey("xl/workbook.xml"))
        assertTrue(entries.containsKey("xl/_rels/workbook.xml.rels"))
        assertTrue(entries.containsKey("xl/worksheets/sheet1.xml"))

        val doc = parseXml(entries.getValue("xl/worksheets/sheet1.xml"))
        val rows = doc.getElementsByTagName("row")
        assertEquals(2, rows.length)

        val cells = doc.getElementsByTagName("c")
        assertEquals(4, cells.length)
        assertEquals("A1", cells.item(0).attributes.getNamedItem("r").textContent)
        assertEquals("B1", cells.item(1).attributes.getNamedItem("r").textContent)
        assertEquals("A2", cells.item(2).attributes.getNamedItem("r").textContent)
        assertEquals("B2", cells.item(3).attributes.getNamedItem("r").textContent)

        val texts = doc.getElementsByTagNameNS("*", "t")
        assertEquals("홍길동", texts.item(2).textContent)
        assertEquals("30", texts.item(3).textContent)
    }

    @Test
    fun `buildXlsx column letters roll over past Z`() {
        val header = (0..27).joinToString("\t") { "c$it" }
        val entries = zipEntries(DocumentBuilder.buildXlsx(header))
        val doc = parseXml(entries.getValue("xl/worksheets/sheet1.xml"))
        val cells = doc.getElementsByTagName("c")
        assertEquals(28, cells.length)
        assertEquals("Z1", cells.item(25).attributes.getNamedItem("r").textContent)
        assertEquals("AA1", cells.item(26).attributes.getNamedItem("r").textContent)
        assertEquals("AB1", cells.item(27).attributes.getNamedItem("r").textContent)
    }

    @Test
    fun `buildXlsx escapes xml special characters in cell values`() {
        val entries = zipEntries(DocumentBuilder.buildXlsx("A & B\t<tag>\t\"quoted\""))
        val doc = parseXml(entries.getValue("xl/worksheets/sheet1.xml"))
        val texts = doc.getElementsByTagNameNS("*", "t")
        assertEquals("A & B", texts.item(0).textContent)
        assertEquals("<tag>", texts.item(1).textContent)
        assertEquals("\"quoted\"", texts.item(2).textContent)
    }
}
