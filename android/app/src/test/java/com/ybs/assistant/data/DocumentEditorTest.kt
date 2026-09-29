package com.ybs.assistant.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentEditorTest {

    private fun buildZip(vararg entries: Pair<String, String>): ByteArray {
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

    private val docxDocumentXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
<w:p><w:r><w:rPr><w:b/></w:rPr><w:t>Hello </w:t></w:r><w:r><w:t>World</w:t></w:r></w:p>
<w:p><w:r><w:t>Foo</w:t></w:r><w:r><w:t>Bar</w:t></w:r></w:p>
<w:p><w:r><w:t>Hello Hello Hello</w:t></w:r></w:p>
</w:body></w:document>"""

    private val stylesXml = "<styles>original-untouched</styles>"

    @Test
    fun `findReplaceDocx replaces within a single run and preserves other parts unchanged`() {
        val original = buildZip("word/document.xml" to docxDocumentXml, "word/styles.xml" to stylesXml)

        val result = DocumentEditor.findReplaceDocx(original, "Hello", "Hi")!!
        assertEquals(4, result.matchCount) // 1 in run "Hello " + 3 in "Hello Hello Hello"

        val entries = zipEntries(result.bytes)
        assertEquals(stylesXml, entries.getValue("word/styles.xml")) // untouched part preserved byte-for-byte

        val doc = parseXml(entries.getValue("word/document.xml"))
        val texts = doc.getElementsByTagNameNS("*", "t")
        assertEquals("Hi ", texts.item(0).textContent)
        assertEquals("World", texts.item(1).textContent)
        assertEquals("Hi Hi Hi", texts.item(4).textContent)

        // formatting on the edited run (w:rPr/w:b) must still be there
        val runs = doc.getElementsByTagNameNS("*", "r")
        val firstRunChildren = (0 until runs.item(0).childNodes.length).map { runs.item(0).childNodes.item(it).localName }
        assertTrue(firstRunChildren.contains("rPr"))
    }

    @Test
    fun `findReplaceDocx does not match text split across separate runs`() {
        val original = buildZip("word/document.xml" to docxDocumentXml, "word/styles.xml" to stylesXml)

        val result = DocumentEditor.findReplaceDocx(original, "FooBar", "Baz")!!
        assertEquals(0, result.matchCount)
        assertTrue(result.bytes.contentEquals(original)) // safe no-op, nothing rewritten
    }

    private val sharedStringsXml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" count="2" uniqueCount="2"><si><t>Seoul</t></si><si><t>Busan</t></si></sst>"""

    private val sheet1Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c><c r="B1"><v>100</v></c></row>
<row r="2"><c r="A2" t="inlineStr"><is><t>Seoul Branch</t></is></c></row>
</sheetData></worksheet>"""

    private val sheet2Xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>
<row r="1"><c r="A1" t="s"><v>0</v></c></row>
</sheetData></worksheet>"""

    @Test
    fun `findReplaceXlsx updates shared strings and inline strings, skips numeric cells and untouched sheets`() {
        val original = buildZip(
            "xl/sharedStrings.xml" to sharedStringsXml,
            "xl/worksheets/sheet1.xml" to sheet1Xml,
            "xl/worksheets/sheet2.xml" to sheet2Xml,
        )

        val result = DocumentEditor.findReplaceXlsx(original, "Seoul", "Incheon")!!
        assertEquals(2, result.matchCount) // 1 in sharedStrings <si>, 1 in sheet1 inlineStr

        val entries = zipEntries(result.bytes)
        assertEquals(sheet2Xml, entries.getValue("xl/worksheets/sheet2.xml")) // no match here -> untouched

        val sharedDoc = parseXml(entries.getValue("xl/sharedStrings.xml"))
        val sharedTexts = sharedDoc.getElementsByTagNameNS("*", "t")
        assertEquals("Incheon", sharedTexts.item(0).textContent)
        assertEquals("Busan", sharedTexts.item(1).textContent)

        val sheet1Doc = parseXml(entries.getValue("xl/worksheets/sheet1.xml"))
        val sheet1Texts = sheet1Doc.getElementsByTagNameNS("*", "t")
        assertEquals("Incheon Branch", sheet1Texts.item(0).textContent)

        val numericCellValue = sheet1Doc.getElementsByTagNameNS("*", "v").item(1).textContent
        assertEquals("100", numericCellValue) // numeric cell untouched
    }

    @Test
    fun `findReplaceXlsx with no match returns zero count and original bytes`() {
        val original = buildZip("xl/sharedStrings.xml" to sharedStringsXml, "xl/worksheets/sheet1.xml" to sheet1Xml)
        val result = DocumentEditor.findReplaceXlsx(original, "Tokyo", "Osaka")!!
        assertEquals(0, result.matchCount)
        assertTrue(result.bytes.contentEquals(original))
    }
}
