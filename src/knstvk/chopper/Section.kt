package knstvk.chopper

import org.apache.commons.lang3.StringEscapeUtils
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import org.apache.lucene.document.TextField
import org.apache.lucene.index.IndexWriter
import org.jsoup.nodes.Element
import java.io.File
import java.io.StringReader
import java.util.*
import java.util.regex.Pattern

class Section (element: Element, level: Int, parent: Section?) {

    val element: Element = element
    val level: Int = level
    val parent: Section? = parent
    val children: MutableList<Section> = ArrayList()
    val ids: MutableList<String> = ArrayList()

    lateinit var headerEl: Element
    lateinit var id: String
    lateinit var title: String
    lateinit var pageTitle: String
    lateinit var tocItem: String

    private val SECTION_NUM_PATTERN = Pattern.compile("""(\d+\.)+ """)

    fun parse() {
        if (parent == null) {
            id = "index"
            tocItem = "Home"
        } else {
            val hEls = element.getElementsByTag("h${level + 1}")
            if (hEls.isEmpty())
                throw IllegalStateException("'h${level + 1}' element is not found in section '${getBeginning()}'")
            this.headerEl = hEls[0]

            val id = headerEl.attr("id")
            if (id.isEmpty())
                throw IllegalStateException("No 'id' attribute in '${getBeginning()}'")
            this.id = id

            title = headerEl.ownText()
            tocItem = SECTION_NUM_PATTERN.matcher(headerEl.ownText()).replaceFirst("")
            pageTitle = tocItem + " - " + getHierarchy()[0].pageTitle

            val ancorEls = headerEl.getElementsByClass("anchor")
            if (ancorEls.isNotEmpty()) {
                val href = ancorEls[0].attr("href")
                if (href.startsWith("#") && href.substring(1) == id) {
                    ancorEls[0].remove()
                }
            }
        }

        val nextLevel = this.level + 1
        val sectElements = element.getElementsByClass("sect$nextLevel")
        for (sectEl in sectElements) {
            val sect = Section(sectEl, nextLevel, this)
            children.add(sect)
            sect.parse()
            sectEl.remove()
        }

        ids.addAll(element.getElementsByAttribute("id").map { it.attr("id") })
    }

    private fun getBeginning(): String = element.outerHtml().substring(0, 100) + "..."

    private fun getHtmlContent(): String {
        val template = if (parent == null) "index.html" else "page.html"
        val html = File("templates", template).readText()
        val next = getNext()
        return html
                .replace("{{pageTitle}}", pageTitle)
                .replace("{{toc}}", createToc())
                .replace("{{content}}", if (parent == null) headerEl.outerHtml() else element.outerHtml())
                .replace("{{next.href}}", next.id + ".html")
                .replace("{{next.text}}", next.title)

    }

    private fun getNext(): Section {
        if (!children.isEmpty())
            return children[0]
        else {
            val hierarchy = getHierarchy().reversed()
            if (hierarchy.size > 1) {
                for (i in hierarchy.indices.drop(1)) {
                    val p = hierarchy[i]
                    val c = hierarchy[i - 1]
                    if (!c.equals(p.children.last())) {
                        val indexOfC = p.children.indexOf(c)
                        return p.children[indexOfC + 1]
                    }
                }
            }
            return hierarchy.last()
        }
    }

    private fun printTocItem(hierarchy: List<Section>): String {
        val sb = StringBuilder()

        val inHierarchy = hierarchy.contains(this)
        val isSelected = this == hierarchy.last()

        sb.append("\n<li class='toc-item'>${getTocMarker(this, inHierarchy)}${getTocA(this, isSelected)}");
        if (inHierarchy) {
            sb.append("\n<ul>")
            for (child in children) {
                sb.append(child.printTocItem(hierarchy))
            }
            sb.append("\n</ul>")
        }
        sb.append("\n</li>")

        return sb.toString()
    }

    private fun createToc(): String {
        val sb = StringBuilder()

        sb.append("\n<ul class='toc-root'>")

        val hierarchy = getHierarchy()
        val root = hierarchy[0]

        sb.append("\n<li>${getTocA(root, false)}</li>");

        for (item in root.children) {
            sb.append(item.printTocItem(hierarchy))
        }

        sb.append("\n</ul>")
        return sb.toString()
    }

    private fun getTocMarker(section: Section, open: Boolean): String {
        val sb = StringBuilder()
        sb.append("<div class='toc-marker")
        if (!section.children.isEmpty()) {
            if (open)
                sb.append(" open")
            else
                sb.append(" closed")
        }
        sb.append("'></div>")
        return sb.toString()
    }

    private fun getTocA(section: Section, active: Boolean): String {
        val sb = StringBuilder()
        sb.append("<div class='toc-link'><a href='${section.id}.html'")
        if (active)
            sb.append(" class='toc-highlighted'")
        sb.append(">${section.tocItem}</a></div>")
        return sb.toString()
    }

    private fun getHierarchy(): List<Section> {
        val parents: MutableList<Section> = ArrayList()
        var p = parent
        while (p != null) {
            parents.add(p)
            p = p.parent
        }
        parents.reverse()
        parents.add(this)
        return parents
    }

    fun write(dir: File, links: MutableMap<String, Section>, indexWriter: IndexWriter?) {
        replaceLinks(links)

        val content = getHtmlContent()

        val fileName = id + ".html"
        val file = File(dir, fileName)
        file.writeText(content, "UTF-8")

        if (indexWriter != null) {
            indexFile(element.text(), fileName, indexWriter)
        }

        for (childSect in children) {
            childSect.write(dir, links, indexWriter)
        }
    }

    fun replaceLinks(links: MutableMap<String, Section>) {
        val aElements = element.getElementsByTag("a")
        for (aEl in aElements) {
            val href = aEl.attr("href")
            if (href.startsWith("#")) {
                val ref = href.substring(1)
                val section = links[ref]
                if (section == null) {
                    println("WARNING: unknown link: $href")
                    continue
                }
                if (section !== this) {
                    val newRef = if (ref == section.id) "${section.id}.html" else "${section.id}.html#$ref"
                    aEl.attr("href", newRef)
                }
            }
        }
    }

    private fun indexFile(contents: String, fileName: String, indexWriter: IndexWriter) {
        val caption: String
        val hierarchy = getHierarchy()
        if (hierarchy.size == 1) {
            caption = tocItem
        } else {
            caption = hierarchy.subList(1, hierarchy.size).map { it.tocItem }.joinToString(" > ")
        }

        val doc = Document()

        doc.add(StringField("fileName", fileName, Field.Store.YES))
        doc.add(StringField("caption", StringEscapeUtils.escapeHtml4(caption), Field.Store.YES))
        doc.add(TextField("contents", StringReader(contents)))

        indexWriter.addDocument(doc)
    }
}
