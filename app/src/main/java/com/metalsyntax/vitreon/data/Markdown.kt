package com.metalsyntax.vitreon.data

/**
 * Markdown → bloques e intervalos de estilo, sin HTML (no hay inyección posible: el texto nunca se interpreta como código).
 * Lo de Vitreon web (#, >, -, `código`, ---, **negrita**, _cursiva_, ++subrayado++) más lo de Obsidian que se usa a diario:
 * [[enlaces]] con alias, #etiquetas, - [ ] tareas, > [!note] callouts, ==resaltado==, ~~tachado~~, ``` bloques ```, tablas
 * simples y frontmatter. La interfaz convierte esto en AnnotatedString; los plugins pueden transformar el texto antes.
 */
sealed interface Block {
    data class Heading(val level: Int, val text: String) : Block
    data class Paragraph(val text: String) : Block
    data class Quote(val text: String) : Block
    data class Callout(val kind: String, val title: String, val body: String) : Block
    /** Línea de lista: [checked] null = viñeta normal; [number] para listas numeradas. [line] = línea en el texto original (para marcar tareas). */
    data class Item(val text: String, val depth: Int, val number: Int? = null, val checked: Boolean? = null, val line: Int = -1) : Block
    data class Code(val lang: String, val text: String) : Block
    data class Table(val header: List<String>, val rows: List<List<String>>) : Block
    data class Properties(val entries: List<Pair<String, String>>) : Block
    data object Rule : Block
}

enum class InlineStyle { BOLD, ITALIC, UNDERLINE, STRIKE, HIGHLIGHT, CODE, LINK, WIKILINK, TAG }

/** Tramo con estilo sobre el texto ya limpio de marcas; [target] = URL, nota enlazada o etiqueta. */
data class Span(val start: Int, val end: Int, val style: InlineStyle, val target: String? = null)
data class Inline(val text: String, val spans: List<Span>)

object Markdown {
    fun blocks(src: String): List<Block> {
        val out = mutableListOf<Block>()
        val lines = src.replace("\r\n", "\n").split('\n')
        var i = 0
        // frontmatter → propiedades (como la vista de lectura de Obsidian)
        if (lines.firstOrNull()?.trim() == "---") {
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (end >= 0) {
                val entries = lines.subList(1, end + 1).filter { ':' in it && !it.startsWith(" ") }.map { it.substringBefore(':').trim() to it.substringAfter(':').trim().trim('"') }
                if (entries.isNotEmpty()) out += Block.Properties(entries)
                i = end + 2
            }
        }
        val para = StringBuilder()
        fun flush() { if (para.isNotBlank()) out += Block.Paragraph(para.toString().trim()); para.clear() }
        while (i < lines.size) {
            val raw = lines[i]; val l = raw.trimEnd(); val t = l.trimStart()
            when {
                t.startsWith("```") -> {
                    flush()
                    val lang = t.removePrefix("```").trim()
                    val body = mutableListOf<String>(); i++
                    while (i < lines.size && !lines[i].trimStart().startsWith("```")) { body += lines[i]; i++ }
                    out += Block.Code(lang, body.joinToString("\n"))
                }
                t.isEmpty() -> flush()
                Regex("^(#{1,6})\\s+(.*)").matchEntire(t) != null -> { flush(); val m = Regex("^(#{1,6})\\s+(.*)").matchEntire(t)!!; out += Block.Heading(m.groupValues[1].length, m.groupValues[2].trimEnd('#', ' ')) }
                t.matches(Regex("^([-*_])(\\s*\\1){2,}\\s*$")) -> { flush(); out += Block.Rule }
                t.startsWith(">") -> {
                    flush()
                    val q = mutableListOf<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith(">")) { q += lines[i].trimStart().removePrefix(">").removePrefix(" "); i++ }
                    i--
                    val first = Regex("^\\[!(\\w+)]([+-])?\\s*(.*)").matchEntire(q.first())
                    out += if (first != null) Block.Callout(first.groupValues[1].lowercase(), first.groupValues[3].ifBlank { first.groupValues[1].replaceFirstChar { it.uppercase() } }, q.drop(1).joinToString("\n"))
                    else Block.Quote(q.joinToString("\n"))
                }
                Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)").matchEntire(l) != null -> {
                    flush()
                    val m = Regex("^(\\s*)([-*+]|\\d+[.)])\\s+(.*)").matchEntire(l)!!
                    val depth = m.groupValues[1].replace("\t", "  ").length / 2
                    val number = m.groupValues[2].trimEnd('.', ')').toIntOrNull()
                    val task = Regex("^\\[([ xX])]\\s*(.*)").matchEntire(m.groupValues[3])
                    out += Block.Item(task?.groupValues?.get(2) ?: m.groupValues[3], depth, number, task?.let { it.groupValues[1] != " " }, i)
                }
                t.startsWith("|") && i + 1 < lines.size && lines[i + 1].trim().matches(Regex("^\\|?\\s*:?-{2,}.*")) -> {
                    flush()
                    fun cells(s: String) = s.trim().trim('|').split('|').map { it.trim() }
                    val header = cells(t); i += 2
                    val rows = mutableListOf<List<String>>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += cells(lines[i]); i++ }
                    i--
                    out += Block.Table(header, rows)
                }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(l) }
            }
            i++
        }
        flush()
        return out
    }

    private val PATTERNS: List<Pair<Regex, InlineStyle>> = listOf(
        Regex("`([^`]+)`") to InlineStyle.CODE,
        Regex("!?\\[\\[([^\\]|]+)(?:\\|([^\\]]+))?]]") to InlineStyle.WIKILINK,
        Regex("\\[([^\\]]+)]\\(([^)\\s]+)\\)") to InlineStyle.LINK,
        Regex("\\*\\*(.+?)\\*\\*|__(.+?)__") to InlineStyle.BOLD,
        Regex("\\+\\+(.+?)\\+\\+") to InlineStyle.UNDERLINE,
        Regex("~~(.+?)~~") to InlineStyle.STRIKE,
        Regex("==(.+?)==") to InlineStyle.HIGHLIGHT,
        Regex("(?<![\\w*])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![\\w*])|(?<![\\w_])_(?!\\s)(.+?)(?<!\\s)_(?![\\w_])") to InlineStyle.ITALIC,
        Regex("(?<![\\p{L}\\p{N}_/#&])#([\\p{L}_][\\p{L}\\p{N}_/\\-]*)") to InlineStyle.TAG,
        Regex("(?<![(\\[])\\bhttps?://[^\\s<>)]+") to InlineStyle.LINK,
    )

    /**
     * Estilos en línea. Se busca la primera marca de cualquier tipo, se aplica y se procesa su interior recursivamente
     * (para **negrita con _cursiva_ adentro**); el código y los enlaces no se re-procesan por dentro.
     */
    fun inline(src: String): Inline {
        val text = StringBuilder(); val spans = mutableListOf<Span>()
        fun walk(s: String) {
            var pos = 0
            while (pos < s.length) {
                val hit = PATTERNS.mapNotNull { (re, st) -> re.find(s, pos)?.let { it to st } }.minByOrNull { it.first.range.first }
                if (hit == null) { text.append(s, pos, s.length); break }
                val (m, st) = hit
                text.append(s, pos, m.range.first)
                val start = text.length
                when (st) {
                    InlineStyle.CODE -> text.append(m.groupValues[1])
                    InlineStyle.WIKILINK -> text.append(m.groupValues[2].ifEmpty { m.groupValues[1].substringBefore('#') })
                    InlineStyle.LINK -> if (m.value.startsWith("[")) walk(m.groupValues[1]) else text.append(m.value)
                    InlineStyle.TAG -> text.append(m.value)
                    else -> walk(m.groupValues.drop(1).first { it.isNotEmpty() })
                }
                val target = when (st) {
                    InlineStyle.WIKILINK -> m.groupValues[1].trim()
                    InlineStyle.LINK -> if (m.value.startsWith("[")) m.groupValues[2] else m.value
                    InlineStyle.TAG -> m.groupValues[1]
                    else -> null
                }
                spans += Span(start, text.length, st, target)
                pos = m.range.last + 1
            }
        }
        walk(src)
        return Inline(text.toString(), spans)
    }

    /** Marca o desmarca la tarea de la línea [line] (tocar la casilla en modo lectura). */
    fun toggleTask(src: String, line: Int): String {
        val lines = src.split('\n').toMutableList()
        if (line !in lines.indices) return src
        val m = Regex("\\[([ xX])]").find(lines[line]) ?: return src
        lines[line] = lines[line].replaceRange(m.range, if (m.groupValues[1] == " ") "[x]" else "[ ]")
        return lines.joinToString("\n")
    }

    /** Texto plano para vistas previas y búsqueda: sin marcas ni frontmatter. */
    fun plain(src: String): String = blocks(src).joinToString("\n") { b ->
        when (b) {
            is Block.Heading -> inline(b.text).text
            is Block.Paragraph -> inline(b.text).text
            is Block.Quote -> inline(b.text).text
            is Block.Callout -> b.title + " " + inline(b.body).text
            is Block.Item -> (if (b.checked == true) "✓ " else "") + inline(b.text).text
            is Block.Code -> b.text
            is Block.Table -> (b.header + b.rows.flatten()).joinToString(" ")
            is Block.Properties, Block.Rule -> ""
        }
    }.replace(Regex("\n{2,}"), "\n").trim()

    /** [[enlaces]] de una nota (para backlinks y para renombrar). */
    fun links(src: String): List<String> = Regex("\\[\\[([^\\]|#]+)").findAll(src).map { it.groupValues[1].trim() }.distinct().toList()
}
