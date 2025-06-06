package com.example.myapplication

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.example.myapplication.qwen.streamQwenResponse
import com.example.myapplication.renderer.StreamMarkdownRenderer
import kotlinx.coroutines.launch

/**
 * MainActivity 主要负责：
 * 1. 初始化 UI 容器
 * 2. 启动大模型流式接口（如 streamQwenResponse）
 * 3. 把每个 delta 片段交给 StreamMarkdownRenderer 渲染
 */
class MainActivity : AppCompatActivity() {

    private lateinit var renderer: StreamMarkdownRenderer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 获取 ScrollView 和 LinearLayout 容器
        val scrollView: ScrollView = findViewById(R.id.scrollView)
        val contentContainer: LinearLayout = findViewById(R.id.contentContainer)

        // 初始化渲染器（专职负责渲染逻辑）
        renderer = StreamMarkdownRenderer(
            context = this,
            lifecycleScope = lifecycleScope,
            scrollView = scrollView,
            contentContainer = contentContainer
        )

        renderer.startRendering() // 启动消费协程

        startStreamingFromLLM()   // 启动大模型输入
    }

    /**
     * 启动大模型流式响应，并将每个增量文本 delta 交给渲染器
     */
    private fun startStreamingFromLLM() {
        val prompt = "返回一个包含很多很多数学公式（行级，前后存在文字）、图片、表格内容..."

        lifecycleScope.launch {
            streamQwenResponse(prompt).collect { delta ->
                renderer.append(delta) // 将新内容送给 renderer 处理
            }
        }
    }
}
