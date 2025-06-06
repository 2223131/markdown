package com.example.myapplication.renderer

import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.Spannable
import android.view.Gravity
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
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

/**
 * StreamMarkdownRenderer 用于处理增量 Markdown 文本并流式渲染到界面。
 * 功能：
 * - 自动拼接流式文本
 * - 自动识别段落/表格/图片/公式等 Markdown 块
 * - 按块渲染到指定容器，并滚动到底部
 * - 对纯文本使用打字机动画
 */
class StreamMarkdownRenderer(
    private val context: Context,
    private val lifecycleScope: LifecycleCoroutineScope,
    private val scrollView: ScrollView,
    private val contentContainer: LinearLayout
) {
    // 初始化 Markwon，支持 Core、表格、图片、HTML、LaTeX 等
    private val markwon: Markwon = Markwon.builder(context)
        .usePlugin(CorePlugin.create())
        .usePlugin(HtmlPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(ImagesPlugin.create())
        .usePlugin(CoilImagesPlugin.create(context))
        .usePlugin(MarkwonInlineParserPlugin.create())
        .usePlugin(JLatexMathPlugin.create(38F) { it.inlinesEnabled(true) })
        .build()

    // 用于拼接临时字符串
    private val buffer = StringBuilder()
    private var tempBuffer = ""

    // 渲染任务队列（块级内容）
    private val renderQueue = Channel<String>(Channel.UNLIMITED)

    /**
     * 启动协程，持续从队列中消费块并渲染
     */
    fun startRendering() {
        lifecycleScope.launch {
            for (block in renderQueue) {
                renderBlock(block)
            }
        }
    }

    /**
     * 外部传入增量文本，自动拼接、提取渲染块
     */
    fun append(delta: String) {
        buffer.append(delta)
        tempBuffer += delta

        val block = extractRenderableBlock(tempBuffer)
        if (block != null) {
            tempBuffer = tempBuffer.substring(block.lengthUsed)
            lifecycleScope.launch {
                renderQueue.send(block.content)
            }
        }
    }

    /**
     * 数据类：一段完整的 Markdown 块 + 被用掉的长度
     */
    private data class MarkdownBlock(val content: String, val lengthUsed: Int)

    /**
     * 尝试从当前 buffer 中提取一个完整的 Markdown 渲染块
     */
    private fun extractRenderableBlock(text: String): MarkdownBlock? {
        val trimmed = text.trimStart()

        // 普通段落（两个换行）
        val paragraphEnd = trimmed.indexOf("\n\n")
        if (paragraphEnd != -1) {
            return MarkdownBlock(trimmed.substring(0, paragraphEnd + 2), paragraphEnd + 2)
        }

        // 图片
        val imageRegex = Regex("""!\[.*?]\(.*?\)""")
        imageRegex.find(trimmed)?.let {
            return MarkdownBlock(it.value, it.range.last + 1)
        }

        // 块级公式（$$ ... $$）
        val blockStart = trimmed.indexOf("$$")
        if (blockStart != -1) {
            val blockEnd = trimmed.indexOf("$$", blockStart + 2)
            if (blockEnd != -1) {
                return MarkdownBlock(trimmed.substring(blockStart, blockEnd + 2), blockEnd + 2)
            }
        }

        // 行内公式 + 标点收尾换行
        val inlineMath = Regex("""(?<!\$)\$(.+?)\$(?!\$)[^。！？.\n]*[。！？.]?\s*\n""")
        inlineMath.find(trimmed)?.let {
            return MarkdownBlock(it.value, it.range.last + 1)
        }

        // 表格
        if (trimmed.contains("|") && trimmed.contains("\n---")) {
            val tableEnd = trimmed.indexOf("\n\n", trimmed.indexOf("\n---"))
            if (tableEnd != -1) {
                return MarkdownBlock(trimmed.substring(0, tableEnd + 2), tableEnd + 2)
            }
        }

        return null
    }

    /**
     * 将 $...$ 转换为 $$...$$，兼容 Markwon 的 LaTeX 渲染
     */
    private fun preprocessMarkdown(input: String): String {
        val inlineMathRegex = Regex("""(?<!\$)\$(?!\$)(.+?)(?<!\$)\$(?!\$)""")
        return input.trim().replace(inlineMathRegex) { match -> "$$${match.groupValues[1]}$$" }
    }

    /**
     * 实际渲染一个块，支持动画、排版、自动滚动
     */
    private suspend fun renderBlock(block: String) {
        val cleaned = preprocessMarkdown(block)

        withContext(Dispatchers.Main) {
            // 外层包裹一个线性布局用于统一样式/动画
            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(24, 24, 24, 24)
            }

            val hasLatex = cleaned.contains("$$")

            val view = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { setMargins(0, 12, 0, 12) }
                textSize = 16f
                setTextColor(Color.BLACK)
                setLineSpacing(0f, 1.5f)
                includeFontPadding = true
                if (hasLatex) setPadding(0, 5, 0, 12)
            }

            // 如果是块级公式则居中
            if (block.trim().startsWith("$$")) {
                view.gravity = Gravity.CENTER
            }

            container.addView(view)
            contentContainer.addView(container)

            val isPlainText = !cleaned.contains("$$")
                    && !cleaned.contains("![")
                    && !cleaned.contains("|")
                    && !cleaned.contains("\\begin")

            if (isPlainText) {
                val node = withContext(Dispatchers.Default) { markwon.parse(cleaned) }
                val rendered = withContext(Dispatchers.Default) { markwon.render(node) }

                (rendered as? Spannable)?.let {
                    val deferred = CompletableDeferred<Unit>()
                    animateTyping(view, it) { deferred.complete(Unit) }
                    deferred.await()
                } ?: markwon.setMarkdown(view, cleaned)
            } else {
                markwon.setMarkdown(view, cleaned)

                // 淡入动画
                container.startAnimation(AlphaAnimation(0f, 1f).apply {
                    duration = 300
                    fillAfter = true
                })
            }

            // 自动滚动到底部
            scrollView.post { scrollView.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    /**
     * 打字机动画显示文本
     */
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
