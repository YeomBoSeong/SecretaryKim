package com.ybs.assistant.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.w3c.dom.Document

/**
 * 기존 .docx/.xlsx 파일의 서식(스타일/이미지/표 구조 등)은 그대로 두고, 텍스트만 찾아서 바꾼다.
 * DocumentBuilder(write_file의 새 파일 생성용)와 달리 zip 안의 다른 파트는 원본 바이트 그대로 복사하고,
 * <w:t>/<t> 같은 텍스트 리프 노드의 문자열만 치환한 뒤 그 파트만 교체한다.
 *
 * 제약: 찾는 문자열이 서로 다른 run(서식 조각)에 걸쳐 나뉘어 있으면 매치되지 않는다 — 안전하게 "못 찾음"으로
 * 처리할 뿐, 구조를 억지로 이어붙이거나 하지 않는다. 대신 매치된 부분은 원본 구조/서식을 100% 보존한다.
 */
object DocumentEditor {

    data class EditResult(val matchCount: Int, val bytes: ByteArray)

    fun findReplaceDocx(original: ByteArray, find: String, replace: String): EditResult? {
        if (find.isEmpty()) return null
        val entries = readZipEntries(original)
        val documentXml = entries["word/document.xml"] ?: return null
        val (count, newXml) = replaceInTextNodes(documentXml, find, replace) ?: return EditResult(0, original)
        entries["word/document.xml"] = newXml
        return EditResult(count, writeZip(entries))
    }

    fun findReplaceXlsx(original: ByteArray, find: String, replace: String): EditResult? {
        if (find.isEmpty()) return null
        val entries = readZipEntries(original)
        var totalCount = 0
        for ((name, bytes) in entries.toMap()) {
            val isTarget = name == "xl/sharedStrings.xml" ||
                (name.startsWith("xl/worksheets/sheet") && name.endsWith(".xml"))
            if (!isTarget) continue
            val (count, newXml) = replaceInTextNodes(bytes, find, replace) ?: continue
            entries[name] = newXml
            totalCount += count
        }
        if (totalCount == 0) return EditResult(0, original)
        return EditResult(totalCount, writeZip(entries))
    }

    /**
     * 문서 XML에서 텍스트 리프 노드(docx의 <w:t>, xlsx의 <t>) 안 문자열만 찾아 바꾼다.
     * 매치가 하나도 없으면 null을 돌려주고, 있으면 (바뀐 횟수, 새 XML 바이트)를 돌려준다.
     */
    private fun replaceInTextNodes(xmlBytes: ByteArray, find: String, replace: String): Pair<Int, ByteArray>? {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        val textNodes = doc.getElementsByTagNameNS("*", "t")
        var count = 0
        for (i in 0 until textNodes.length) {
            val node = textNodes.item(i)
            val text = node.textContent ?: continue
            val occurrences = countOccurrences(text, find)
            if (occurrences > 0) {
                count += occurrences
                node.textContent = text.replace(find, replace)
                (node as? org.w3c.dom.Element)?.setAttribute("xml:space", "preserve")
            }
        }
        if (count == 0) return null
        return count to serializeXml(doc)
    }

    private fun countOccurrences(text: String, find: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(find, index)
            if (index < 0) break
            count++
            index += find.length
        }
        return count
    }

    private fun serializeXml(doc: Document): ByteArray {
        val out = ByteArrayOutputStream()
        val transformer = TransformerFactory.newInstance().newTransformer()
        transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8")
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "no")
        transformer.setOutputProperty(OutputKeys.INDENT, "no")
        transformer.transform(DOMSource(doc), StreamResult(out))
        return out.toByteArray()
    }

    private fun readZipEntries(bytes: ByteArray): LinkedHashMap<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                map[entry.name] = zip.readBytes()
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
        return map
    }

    private fun writeZip(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            for ((name, data) in entries) {
                zos.putNextEntry(ZipEntry(name))
                zos.write(data)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }
}
