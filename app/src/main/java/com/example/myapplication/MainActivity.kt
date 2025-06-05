package com.example.myapplication

import android.os.Bundle
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.qwen.streamQwenResponse
import io.noties.markwon.*
import io.noties.markwon.core.CorePlugin
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.html.HtmlPlugin
import io.noties.markwon.image.ImagesPlugin
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var markwon: Markwon

    private val buffer = StringBuilder()
    private var tempBuffer = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        scrollView = findViewById(R.id.scrollView)
        contentContainer = findViewById(R.id.contentContainer)

        // 初始化 Markwon
        markwon = Markwon.builder(this)
            .usePlugin(CorePlugin.create())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .usePlugin(ImagesPlugin.create())
            .usePlugin(CoilImagesPlugin.create(this))
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(32F) { it.inlinesEnabled(true) }) // ✅ 支持 $...$
            .build()

        startStreaming()
    }

    private fun startStreaming() {
        val prompt = "返回一个包含数学公式（行级和块级）、图片、表格内容，图片链接是：https://tse2.mm.bing.net/th/id/OIP.C6DF0hkbhkgRdoOpjfb-9gHaHa?rs=1&pid=ImgDetMain"

        lifecycleScope.launch {
            streamQwenResponse(prompt).collect { delta ->
                buffer.append(delta)
                tempBuffer += delta

                val blockData = extractRenderableBlock(tempBuffer)
                if (blockData != null) {
                    tempBuffer = tempBuffer.substring(blockData.lengthUsed)
                    renderBlock(blockData.content)
                }
            }
        }
    }

    // 封装 Markdown 块和它的长度
    data class MarkdownBlock(val content: String, val lengthUsed: Int)

    private fun extractRenderableBlock(text: String): MarkdownBlock? {
        val trimmed = text.trimStart()

        // 完整段落（两个换行）
        val paragraphEnd = trimmed.indexOf("\n\n")
        if (paragraphEnd != -1) {
            return MarkdownBlock(trimmed.substring(0, paragraphEnd + 2), paragraphEnd + 2)
        }

        // 图片 ![alt](url)
        val imageRegex = Regex("""!\[.*?]\(.*?\)""")
        val imageMatch = imageRegex.find(trimmed)
        if (imageMatch != null) {
            return MarkdownBlock(imageMatch.value, imageMatch.range.last + 1)
        }

        // 行内公式 $...$
        val inlineMath = Regex("""(?<!\$)\$(.+?)\$(?!\$)""")
        val inlineMatch = inlineMath.find(trimmed)
        if (inlineMatch != null) {
            return MarkdownBlock(inlineMatch.value, inlineMatch.range.last + 1)
        }

        // 块级公式 $$...$$
        val blockMath = Regex("""\$\$(.*?)\$\$""", RegexOption.DOT_MATCHES_ALL)
        val blockMatch = blockMath.find(trimmed)
        if (blockMatch != null) {
            return MarkdownBlock(blockMatch.value, blockMatch.range.last + 1)
        }

        // 表格（带分隔行 ---）
        if (trimmed.contains("|") && trimmed.contains("\n---")) {
            val tableEnd = trimmed.indexOf("\n\n", trimmed.indexOf("\n---"))
            if (tableEnd != -1) {
                return MarkdownBlock(trimmed.substring(0, tableEnd + 2), tableEnd + 2)
            }
        }

        return null
    }

    private fun preprocessMarkdown(input: String): String {
        var result = input.trim()
        result = result.replace(Regex("""(?<!\$)\$(.+?)\$(?!\$)""")) { match ->
            "$$${match.groupValues[1]}$$"
        }
        return result
    }


    private suspend fun renderBlock(block: String) {
        val cleaned = preprocessMarkdown(block)

        val node = withContext(Dispatchers.Default) {
            markwon.parse(cleaned)
        }

        val rendered = withContext(Dispatchers.Default) {
            markwon.render(node)
        }

        withContext(Dispatchers.Main) {
            val view = TextView(this@MainActivity).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                textSize = 16f
            }
            markwon.setParsedMarkdown(view, rendered)
            contentContainer.addView(view)

            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
