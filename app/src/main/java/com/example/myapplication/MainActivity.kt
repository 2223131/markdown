package com.example.myapplication

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
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
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

class MainActivity : AppCompatActivity() {

    private lateinit var scrollView: ScrollView
    private lateinit var contentContainer: LinearLayout
    private lateinit var markwon: Markwon

    private val buffer = StringBuilder()
    private var tempBuffer = ""

    // 渲染任务队列
    private val renderQueue = Channel<String>(Channel.UNLIMITED)

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
        startRendering()
    }

    private fun startStreaming() {
        val prompt = "返回一个包含数学公式（行级，复杂的，多个）、图片、表格内容，图片链接是：https://tse2.mm.bing.net/th/id/OIP.C6DF0hkbhkgRdoOpjfb-9gHaHa?rs=1&pid=ImgDetMain"

        lifecycleScope.launch {
            streamQwenResponse(prompt).collect { delta ->
                buffer.append(delta)
                tempBuffer += delta

                val blockData = extractRenderableBlock(tempBuffer)
                if (blockData != null) {
                    tempBuffer = tempBuffer.substring(blockData.lengthUsed)
                    renderQueue.send(blockData.content)
                }
            }
        }
    }

    private fun startRendering() {
        lifecycleScope.launch {
            for (block in renderQueue) {
                renderBlock(block)
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

                // 检查是否为单独一行（前后是换行或开始/结尾）
                val before = trimmed.substring(0, blockStart)
                val after = trimmed.substring(blockEnd + 2)

                val isStandalone = before.endsWith("\n") && after.startsWith("\n")
                if (isStandalone) {
                    return MarkdownBlock(mathBlock, blockEnd + 2)
                }
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
        val result = input.trim()
        Log.d("替换前", result)
        // 将标准行内 $...$ 替换为 Markwon 需要的 $$...$$（行内，无换行）
        return result.replace(Regex("""(?<!\$)\$(.+?)\$(?!\$)""")) { match ->
            "$$${match.groupValues[1]}$$"
        }
    }

    private suspend fun renderBlock(block: String) {
        val cleaned = preprocessMarkdown(block)
        Log.d("替换后", cleaned)

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
                setPadding(24, 24, 24, 24)
            }

            val view = TextView(this@MainActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 12, 0, 12)
                }
                textSize = 16f
                setTextColor(Color.BLACK)
            }

            if (block.trim().startsWith("$$")) {
                view.gravity = Gravity.CENTER
            }

            container.addView(view)
            contentContainer.addView(container)

            val isPlainText = !block.contains("$$") &&
                    !block.contains("![") &&
                    !block.contains("|") &&
                    !block.contains("\\begin") &&
                    !block.contains("<img")

            if (isPlainText) {
                val spannable = rendered as? Spannable
                if (spannable != null) {
                    val deferred = CompletableDeferred<Unit>()
                    animateTyping(view, spannable) {
                        deferred.complete(Unit)
                    }
                    deferred.await() // 等待动画完成再处理下一个 block
                } else {
                    markwon.setParsedMarkdown(view, rendered)
                }
            } else {
                markwon.setParsedMarkdown(view, rendered)
            }

            if (!isPlainText) {
                val fadeIn = AlphaAnimation(0f, 1f).apply {
                    duration = 300
                    fillAfter = true
                }
                container.startAnimation(fadeIn)
            }

            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }

    private fun animateTyping(view: TextView, fullText: CharSequence, onComplete: () -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        var index = 0
        val delay = 20L

        handler.post(object : Runnable {
            override fun run() {
                if (index <= fullText.length) {
                    view.text = fullText.subSequence(0, index)
                    index++
                    handler.postDelayed(this, delay)
                } else {
                    onComplete()
                }
            }
        })
    }
}