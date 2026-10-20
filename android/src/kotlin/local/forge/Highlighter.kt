package local.forge

import java.util.regex.Pattern

/**
 * Regex tokenizers for the code editor, one per language, with the same colors as the web page's Monaco theme:
 * comments 6b7489, keywords c792ea, strings c3e88d, numbers f78c6c, types ffcb6b, functions 82aaff, tags ff8a65 and
 * attribute names ffcb6b. Plain identifiers keep the editor's text color, so they get no span at all.
 *
 * A language is a list of rules. Every rule is a regex without capturing groups, and the rules are joined into one
 * alternation, so one pass of the matcher over a region of text finds every token. The editor only ever hands a
 * region (the visible lines plus a margin) to tokenize(), and it does so on a background thread.
 */
object Highlighter {
    val COMMENT = 0xff6b7489.toInt()
    val KEYWORD = 0xffc792ea.toInt()
    val STRING = 0xffc3e88d.toInt()
    val NUMBER = 0xfff78c6c.toInt()
    val TYPE = 0xffffcb6b.toInt()
    val FUNCTION = 0xff82aaff.toInt()
    val TAG = 0xffff8a65.toInt()
    val ATTR = 0xffffcb6b.toInt()

    class Token(val start: Int, val end: Int, val color: Int)

    /** One token rule: a regex (no capturing groups) and the color for its match, or 0 for no color. */
    class Rule(val regex: String, val color: (String) -> Int) {
        constructor(regex: String, fixed: Int) : this(regex, { _ -> fixed })
    }

    /**
     * A language: its tokenizer, its tab width, and how a line comment is written (null when the language only has
     * block comments, in which case blockComment is used by the editor's comment toggle).
     */
    class Lang(val id: String, val tabSize: Int, val lineComment: String?, val blockComment: Pair<String, String>?, private val rules: List<Rule>) {
        private val pattern: Pattern? by lazy {
            if (rules.isEmpty()) null else Pattern.compile(rules.joinToString("|") { "(" + it.regex + ")" }, Pattern.MULTILINE)
        }

        /** Tokenizes one region of text; base is the region's offset in the whole document. */
        fun tokenize(text: String, base: Int, out: MutableList<Token>) {
            val p = pattern ?: return
            val m = p.matcher(text)
            val n = rules.size
            while (m.find()) {
                if (m.end() == m.start()) continue
                var g = 1
                while (g <= n && m.group(g) == null) g++
                if (g > n) continue
                val color = rules[g - 1].color(m.group(g) ?: "")
                if (color != 0) out.add(Token(base + m.start(), base + m.end(), color))
            }
        }
    }

    // ------------------------------------------------------------------ building blocks (Java regex syntax)
    private const val C_COMMENT = """//[^\n]*|/\*[\s\S]*?\*/|/\*[\s\S]*"""
    private const val BLOCK_COMMENT = """/\*[\s\S]*?\*/|/\*[\s\S]*"""
    private const val HASH_COMMENT = """#[^\n]*"""
    private const val Q2 = """\x22(?:\\.|[^\x22\\\n])*\x22"""
    private const val Q1 = """'(?:\\.|[^'\\\n])*'"""
    private const val BACKTICK = """`(?:\\.|[^`\\])*`"""
    private const val TRIPLE2 = """\x22\x22\x22[\s\S]*?\x22\x22\x22"""
    private const val TRIPLE1 = """'''[\s\S]*?'''"""
    private const val NUMBER_RE = """\b(?:0[xX][0-9a-fA-F_]+|0[bB][01_]+|0[oO][0-7_]+|\d[\d_]*(?:\.\d[\d_]*)?(?:[eE][+-]?\d+)?)[uUlLfFdDn]*\b"""
    private const val IDENT = """[A-Za-z_\x24][\w\x24]*"""
    private const val ANNOTATION = """@[A-Za-z_][\w.]*"""

    private fun words(s: String): Set<String> = s.split(' ').filter { it.isNotEmpty() }.toHashSet()

    /** The two identifier rules: a word followed by "(" is a function call unless it is a keyword or a type. */
    private fun identRules(keywords: Set<String>, types: Set<String>, capitalIsType: Boolean): List<Rule> {
        fun isType(w: String): Boolean {
            if (w in types) return true
            if (!capitalIsType || !w[0].isUpperCase()) return false
            // ALL_CAPS words are constants, not types; a single capital letter (a generic parameter) is a type.
            return w.length == 1 || w.any { it.isLowerCase() }
        }
        return listOf(
            Rule("""$IDENT(?=\s*\()""") { w -> when { w in keywords -> KEYWORD; isType(w) -> TYPE; else -> FUNCTION } },
            Rule(IDENT) { w -> when { w in keywords -> KEYWORD; isType(w) -> TYPE; else -> 0 } })
    }

    private fun cLike(id: String, keywords: String, types: String, tab: Int = 4, extra: List<Rule> = emptyList(), strings: List<Rule> = listOf(Rule(Q2, STRING), Rule(Q1, STRING)), capitalIsType: Boolean = true): Lang {
        val rules = ArrayList<Rule>()
        rules.add(Rule(C_COMMENT, COMMENT))
        rules.addAll(strings)
        rules.addAll(extra)
        rules.add(Rule(NUMBER_RE, NUMBER))
        rules.addAll(identRules(words(keywords), words(types), capitalIsType))
        return Lang(id, tab, "//", "/*" to "*/", rules)
    }

    // ------------------------------------------------------------------ the languages
    val plain = Lang("plaintext", 4, null, null, emptyList())

    private val kotlin = cLike("kotlin",
        "as break class continue do else false for fun if in interface is null object package return super this throw true try typealias val var when while by catch constructor delegate dynamic field file finally get import init param property receiver set setparam where actual abstract annotation companion const crossinline data enum expect external final infix inline inner internal lateinit noinline open operator out override private protected public reified sealed suspend tailrec vararg value",
        "Int Long Short Byte Double Float Boolean Char String Unit Any Nothing List Map Set Array MutableList MutableMap",
        extra = listOf(Rule(ANNOTATION, ATTR)), strings = listOf(Rule(TRIPLE2, STRING), Rule(Q2, STRING), Rule(Q1, STRING)))

    private val java = cLike("java",
        "abstract assert boolean break byte case catch char class const continue default do double else enum extends final finally float for goto if implements import instanceof int interface long native new package private protected public return short static strictfp super switch synchronized this throw throws transient try void volatile while true false null var record sealed permits yield non-sealed",
        "String Object Integer Long Short Byte Double Float Boolean Character List Map Set ArrayList HashMap",
        extra = listOf(Rule(ANNOTATION, ATTR)), strings = listOf(Rule(TRIPLE2, STRING), Rule(Q2, STRING), Rule(Q1, STRING)))

    private val python: Lang = run {
        val rules = ArrayList<Rule>()
        rules.add(Rule(HASH_COMMENT, COMMENT))
        rules.add(Rule("""[rRbBuUfF]{0,2}(?:$TRIPLE2|$TRIPLE1)""", STRING))
        rules.add(Rule("""[rRbBuUfF]{0,2}(?:$Q2|$Q1)""", STRING))
        rules.add(Rule("""@[A-Za-z_][\w.]*""", ATTR))
        rules.add(Rule(NUMBER_RE, NUMBER))
        rules.addAll(identRules(
            words("False None True and as assert async await break class continue def del elif else except finally for from global if import in is lambda nonlocal not or pass raise return try while with yield match case"),
            words("int str float bool list dict set tuple bytes object Exception complex frozenset bytearray range type"), true))
        Lang("python", 4, "#", null, rules)
    }

    private const val JS_KEYWORDS = "async await break case catch class const continue debugger default delete do else export extends finally for from function if import in instanceof let new of return static super switch this throw try typeof var void while with yield true false null undefined NaN Infinity get set as"
    private val javascript = cLike("javascript", JS_KEYWORDS, "Promise Array Object String Number Boolean Map Set Date RegExp Error JSON Math console document window", tab = 2,
        strings = listOf(Rule(BACKTICK, STRING), Rule(Q2, STRING), Rule(Q1, STRING)))
    private val typescript = cLike("typescript",
        "$JS_KEYWORDS type interface enum implements declare namespace module abstract readonly keyof infer is public private protected override satisfies",
        "any unknown never number string boolean symbol bigint object void Promise Array Object String Number Boolean Map Set Date RegExp Error JSON Math console document window", tab = 2,
        extra = listOf(Rule(ANNOTATION, ATTR)), strings = listOf(Rule(BACKTICK, STRING), Rule(Q2, STRING), Rule(Q1, STRING)))

    private const val C_KEYWORDS = "auto break case char const continue default do double else enum extern float for goto if inline int long register restrict return short signed sizeof static struct switch typedef union unsigned void volatile while _Bool _Complex true false NULL"
    private val cTypes = "size_t ssize_t uint8_t uint16_t uint32_t uint64_t int8_t int16_t int32_t int64_t bool FILE"
    private val preprocessor = Rule("""^[ \t]*#[ \t]*[A-Za-z_]+""", KEYWORD)
    private val c = cLike("c", C_KEYWORDS, cTypes, extra = listOf(preprocessor))
    private val cpp = cLike("cpp",
        "$C_KEYWORDS alignas alignof and asm bool catch class compl concept consteval constexpr constinit const_cast co_await co_return co_yield decltype delete dynamic_cast explicit export friend mutable namespace new noexcept not nullptr operator or override private protected public reinterpret_cast requires static_assert static_cast template this thread_local throw try typeid typename using virtual wchar_t xor final",
        "$cTypes string vector map set unordered_map shared_ptr unique_ptr", extra = listOf(preprocessor))

    private val rust = cLike("rust",
        "as async await break const continue crate dyn else enum extern false fn for if impl in let loop match mod move mut pub ref return self Self static struct super trait true type unsafe use where while macro_rules",
        "i8 i16 i32 i64 i128 isize u8 u16 u32 u64 u128 usize f32 f64 bool char str String Vec Option Result Box Rc Arc HashMap HashSet",
        extra = listOf(Rule("""#!?\[[^\]\n]*\]""", ATTR), Rule("""[A-Za-z_]\w*!(?=[\s(\[{])""", FUNCTION)),
        strings = listOf(Rule(Q2, STRING), Rule("""'(?:\\.|[^'\\\n])'""", STRING)))

    private val go = cLike("go",
        "break case chan const continue default defer else fallthrough for func go goto if import interface map package range return select struct switch type var true false nil iota",
        "bool byte complex64 complex128 error float32 float64 int int8 int16 int32 int64 rune string uint uint8 uint16 uint32 uint64 uintptr any",
        strings = listOf(Rule(BACKTICK, STRING), Rule(Q2, STRING), Rule("""'(?:\\.|[^'\\\n])'""", STRING)),
        capitalIsType = false) // Go exports functions with a capital letter, so a capital does not mean a type here

    private val shell: Lang = run {
        val keywords = words("if then else elif fi for while until do done case esac in function select time return exit export local readonly declare unset set shift source alias eval exec echo cd test break continue")
        val rules = ArrayList<Rule>()
        rules.add(Rule(HASH_COMMENT, COMMENT))
        rules.add(Rule("""\x22(?:\\.|[^\x22\\])*\x22""", STRING))
        rules.add(Rule("""'[^']*'""", STRING))
        rules.add(Rule("""\x24\{[^}\n]*\}|\x24[\w@#?*!\x24-]+""", TYPE))
        rules.add(Rule("""^[ \t]*[A-Za-z_][\w-]*(?=[ \t]*\(\))""", FUNCTION))
        rules.add(Rule(NUMBER_RE, NUMBER))
        rules.add(Rule("""^[ \t]*[A-Za-z_][\w./-]*(?![\w=])""") { w -> if (w.trim() in keywords) KEYWORD else FUNCTION })
        rules.add(Rule("""[A-Za-z_][\w-]*""") { w -> if (w in keywords) KEYWORD else 0 })
        Lang("shell", 4, "#", null, rules)
    }

    private val dockerfile: Lang = run {
        val keywords = words("FROM RUN CMD LABEL EXPOSE ENV ADD COPY ENTRYPOINT VOLUME USER WORKDIR ARG ONBUILD STOPSIGNAL HEALTHCHECK SHELL AS MAINTAINER")
        Lang("dockerfile", 4, "#", null, listOf(
            Rule(HASH_COMMENT, COMMENT), Rule(Q2, STRING), Rule(Q1, STRING),
            Rule("""\x24\{[^}\n]*\}|\x24\w+""", TYPE), Rule(NUMBER_RE, NUMBER),
            Rule("""^[A-Z]+\b""") { w -> if (w in keywords) KEYWORD else 0 }))
    }

    private val json = Lang("json", 2, "//", "/*" to "*/", listOf(
        Rule(C_COMMENT, COMMENT),
        Rule("""\x22(?:\\.|[^\x22\\])*\x22(?=\s*:)""", ATTR),
        Rule("""\x22(?:\\.|[^\x22\\])*\x22""", STRING),
        Rule("""-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b""", NUMBER),
        Rule("""\b(?:true|false|null)\b""", KEYWORD)))

    private val yaml = Lang("yaml", 2, "#", null, listOf(
        Rule(HASH_COMMENT, COMMENT),
        Rule("""^(?:---|\.\.\.)[ \t]*$""", KEYWORD),
        Rule("""^[ \t]*(?:-[ \t]+)?(?:\x22(?:\\.|[^\x22\\\n])*\x22|'[^'\n]*'|[\w.\-/ ]*[\w.\-/])(?=[ \t]*:(?:[ \t]|$))""", ATTR),
        Rule(Q2, STRING), Rule("""'[^'\n]*'""", STRING),
        Rule("""[&*][\w-]+|!![\w-]+""", TYPE),
        Rule("""\b(?:true|false|null|yes|no|on|off|True|False|Yes|No|Null)\b|(?<=\s)~(?=\s|$)""", KEYWORD),
        Rule("""(?<![\w.])-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?(?![\w.])""", NUMBER)))

    private val ini = Lang("ini", 4, "#", null, listOf(
        Rule("""^[ \t]*[#;][^\n]*""", COMMENT),
        Rule("""^[ \t]*\[[^\]\n]*\]""", TYPE),
        Rule("""^[ \t]*[\w.\-]+(?=[ \t]*=)""", ATTR),
        Rule(TRIPLE2, STRING), Rule(Q2, STRING), Rule(Q1, STRING),
        Rule("""\b(?:true|false|yes|no|on|off)\b""", KEYWORD),
        Rule(NUMBER_RE, NUMBER)))

    private val markdown = Lang("markdown", 4, null, "<!--" to "-->", listOf(
        Rule("""^[ \t]*(?:```|~~~)[^\n]*\n[\s\S]*?\n[ \t]*(?:```|~~~)[ \t]*$|^[ \t]*(?:```|~~~)[^\n]*\n[\s\S]*""", STRING),
        Rule("""<!--[\s\S]*?-->""", COMMENT),
        Rule("""^#{1,6}[ \t][^\n]*""", KEYWORD),
        Rule("""^>[^\n]*""", COMMENT),
        Rule("""^(?:-{3,}|\*{3,}|_{3,})[ \t]*$""", COMMENT),
        Rule("""`[^`\n]+`""", STRING),
        Rule("""!?\[[^\]\n]*\]\([^)\n]*\)""", FUNCTION),
        Rule("""\*\*[^*\n]+\*\*|__[^_\n]+__""", TYPE),
        Rule("""^[ \t]*(?:[-*+]|\d+\.)(?=[ \t])""", NUMBER),
        Rule("""</?[A-Za-z][^>\n]*>""", TAG)))

    private val html = Lang("html", 2, null, "<!--" to "-->", listOf(
        Rule("""<!--[\s\S]*?-->""", COMMENT),
        Rule("""<!(?:DOCTYPE|doctype)[^>]*>|<\?[\s\S]*?\?>""", KEYWORD),
        Rule("""</?[A-Za-z][\w:.-]*|/?>""", TAG),
        Rule("""[A-Za-z_:][\w:.-]*(?=\s*=)""", ATTR),
        Rule(Q2, STRING), Rule(Q1, STRING),
        Rule("""&#?\w+;""", NUMBER)))

    private val css = Lang("css", 2, null, "/*" to "*/", listOf(
        Rule(BLOCK_COMMENT, COMMENT),
        Rule(Q2, STRING), Rule(Q1, STRING),
        Rule("""@[\w-]+""", KEYWORD),
        Rule("""!important\b""", KEYWORD),
        Rule("""#[0-9a-fA-F]{3,8}\b""", NUMBER),
        Rule("""-?(?:\d+\.?\d*|\.\d+)(?:px|em|rem|%|vh|vw|vmin|vmax|s|ms|deg|fr|pt|ch|ex)?(?![\w.])""", NUMBER),
        Rule("""[\w-]+(?=\s*\()""", FUNCTION),
        Rule("""[\w-]+(?=\s*:)""", ATTR),
        Rule("""[.#][A-Za-z_-][\w-]*""", TYPE),
        Rule("""::?[A-Za-z-]+""", KEYWORD)))

    private val byId: Map<String, Lang> = listOf(kotlin, java, python, javascript, typescript, c, cpp, rust, go, shell, dockerfile, json, yaml, ini, markdown, html, css, plain).associateBy { it.id }

    private val extensions = mapOf(
        "py" to "python", "pyw" to "python", "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript",
        "ts" to "typescript", "tsx" to "typescript", "json" to "json", "jsonc" to "json", "html" to "html", "htm" to "html", "xml" to "html",
        "svg" to "html", "vue" to "html", "css" to "css", "scss" to "css", "less" to "css", "md" to "markdown", "markdown" to "markdown",
        "sh" to "shell", "bash" to "shell", "zsh" to "shell", "yml" to "yaml", "yaml" to "yaml", "toml" to "ini", "ini" to "ini",
        "cfg" to "ini", "conf" to "ini", "properties" to "ini", "env" to "ini", "java" to "java", "gradle" to "java", "kt" to "kotlin",
        "kts" to "kotlin", "go" to "go", "rs" to "rust", "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "cxx" to "cpp",
        "hpp" to "cpp", "hh" to "cpp", "txt" to "plaintext", "log" to "plaintext")

    /** The language id for a file, decided by its extension or by a well known base name. */
    fun languageId(path: String): String {
        val base = path.substringAfterLast('/').lowercase()
        if (base == "dockerfile" || base.startsWith("dockerfile.")) return "dockerfile"
        if (base == "makefile" || base == "gnumakefile") return "shell"
        if (base == ".gitignore" || base == ".gitattributes" || base == ".dockerignore") return "ini"
        val ext = if (base.contains('.')) base.substringAfterLast('.') else ""
        return extensions[ext] ?: "plaintext"
    }

    fun lang(id: String): Lang = byId[id] ?: plain
    fun allIds(): List<String> = byId.keys.sorted()
    fun langFor(path: String): Lang = lang(languageId(path))
}
