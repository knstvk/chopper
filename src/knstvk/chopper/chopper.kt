package knstvk.chopper

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.util.*

fun main(args: Array<String>) {
//    val srcDir = File("""input\simple\en\html-single""")
//    val input = File(srcDir, "simple.html")
    val srcDir = File("""input\manual\en\html-single""")
    val input = File(srcDir, "manual.html")
    val doc = Jsoup.parse(input, "UTF-8")

    val headerEl = doc.getElementById("header") ?: throw IllegalStateException("Element with id='header' is not found")
    val contentEl = doc.getElementById("content") ?: throw IllegalStateException("Element with id='content' is not found")
    doc.getElementById("toc")?.remove()
    val rootSect = Section(contentEl, 0, null)
    rootSect.headerEl = headerEl

    val docTitle = headerEl.getElementsByTag("h1").first()?.ownText()
    rootSect.title = docTitle ?: "Index"
    rootSect.parse()

    val links: MutableMap<String, Section> = HashMap()
    collectLinks(rootSect, links)

    val outputDir = File("output")
    if (outputDir.exists()) {
        outputDir.deleteRecursively()
    }
    outputDir.mkdir()

    srcDir.listFiles { file: File -> file.isDirectory }.forEach {
        it.copyRecursively(File(outputDir, it.name))
    }

    val warTemplDir = File("templates", "war")
    warTemplDir.listFiles().forEach {
        it.copyRecursively(File(outputDir, it.name))
    }

    rootSect.write(outputDir, links)
}

fun collectLinks(sect: Section, links: MutableMap<String, Section>) {
    if (sect.parent != null) {
        for (id in sect.ids) {
            links.put(id, sect)
        }
    }
    for (childSect in sect.children) {
        collectLinks(childSect, links)
    }
}
