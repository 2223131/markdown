package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
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
import kotlinx.coroutines.delay
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

        markwon = Markwon.builder(this)
            .usePlugin(CorePlugin.create())
            .usePlugin(HtmlPlugin.create())
            .usePlugin(TablePlugin.create(this))
            .usePlugin(ImagesPlugin.create())
            .usePlugin(CoilImagesPlugin.create(this))
            .usePlugin(MarkwonInlineParserPlugin.create())
            .usePlugin(JLatexMathPlugin.create(38F) { it.inlinesEnabled(true) })
            .build()

        startStreaming()
    }

    private fun startStreaming() {
//        val prompt = "返回一个包含数学公式（行级和块级，复杂的，多个）、图片、表格内容，图片链接是：https://tse2.mm.bing.net/th/id/OIP.C6DF0hkbhkgRdoOpjfb-9gHaHa?rs=1&pid=ImgDetMain"
        val prompt = "将markdown的所有格式输出出来"

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

    data class MarkdownBlock(val content: String, val lengthUsed: Int)

    private fun extractRenderableBlock(text: String): MarkdownBlock? {
        val trimmed = text.trimStart()

        val paragraphEnd = trimmed.indexOf("\n\n")
        if (paragraphEnd != -1) {
            return MarkdownBlock(trimmed.substring(0, paragraphEnd + 2), paragraphEnd + 2)
        }

        val imageRegex = Regex("""!\[.*?]\(.*?\)""")
        val imageMatch = imageRegex.find(trimmed)
        if (imageMatch != null) {
            return MarkdownBlock(imageMatch.value, imageMatch.range.last + 1)
        }

        val blockStart = trimmed.indexOf("$$")
        if (blockStart != -1) {
            val blockEnd = trimmed.indexOf("$$", blockStart + 2)
            if (blockEnd != -1) {
                val mathBlock = trimmed.substring(blockStart, blockEnd + 2)
                return MarkdownBlock(mathBlock, blockEnd + 2)
            } else {
                return null
            }
        }

        val inlineMathWithSentence = Regex("""(?<!\$)\$(.+?)\$(?!\$)[^。！？.\n]*[。！？.]?\s*\n""")
        val inlineMatch = inlineMathWithSentence.find(trimmed)
        if (inlineMatch != null) {
            return MarkdownBlock(inlineMatch.value, inlineMatch.range.last + 1)
        }

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
            val container = LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(16, 16, 16, 16)
            }

            val view = TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 12, 0, 12)
                }
                textSize = 16f
            }

            val isFormulaOrImageOrTable =
                block.trim().startsWith("$$") || block.contains("![") || block.contains("|")

            if (isFormulaOrImageOrTable) {
                markwon.setParsedMarkdown(view, rendered)
            } else {
                // 👇 在这里我们使用原始 Markdown 内容做动画（不要用 rendered.toString()）
                animateMarkdownText(view, cleaned)
            }

            container.addView(view)
            contentContainer.addView(container)

            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun animateMarkdownText(textView: TextView, markdown: String, delay: Long = 20L) {
        textView.text = ""
        lifecycleScope.launch {
            val builder = StringBuilder()
            for (char in markdown) {
                builder.append(char)
                markwon.setMarkdown(textView, builder.toString())
                delay(delay)
            }
        }
    }

}
