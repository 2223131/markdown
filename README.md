
# Qwen 流式 Markdown 渲染示例（Android）

本项目展示了如何在 Android 应用中，结合通义千问（Qwen）API 实现 Markdown 流式内容的生成与渲染。适用于 AI 文本生成类应用、教育类内容展示、富文本对话系统等场景。

项目核心由以下三个文件组成，分别负责：**数据获取（API 接口）**、**内容渲染（Markdown 支持）** 和 **基础配置（权限及启动项）**。

---

## 一、`streamQwenResponse.kt` — Qwen 响应流处理器

该文件封装了一个 `Flow<String>`，用于从 Qwen 接口流式接收文本增量。

### 核心功能：

- 构造 Qwen 请求（POST JSON + SSE 流式传输）
- 支持超时为 0，保持连接不断开
- 解析流式响应中的新增文本，并通过 `emit` 实时传递给 UI 层

### 请求示意图：

```
Client
   |
   |-- POST --> https://dashscope.aliyuncs.com/...
   |           (with prompt, stream=true)
   |
   <-- SSE -- data: {"output": {"text": "..."}}
   <-- SSE -- data: {"output": {"text": "..."}}
   <-- SSE -- data: [DONE]
```

### 核心接口示例：

```kotlin
fun streamQwenResponse(prompt: String): Flow<String>
```

---

## 二、`StreamMarkdownRenderer.kt` — Markdown 渲染器（支持打字动画）

该类负责将流式返回的文本拼接成完整 Markdown 块，并渲染到 UI。

### 功能概览：

- 自动识别 Markdown 结构：段落、表格、图片、LaTeX（公式）
- 渲染逻辑基于 `Markwon`，支持插件扩展
- 对普通文本应用“打字机”动画，增强交互体验
- 渲染完成后自动滚动到底部

### 支持的 Markdown 类型：

| 类型     | 支持 | 说明                         |
|----------|------|------------------------------|
| 段落     | ✔    | 两个换行自动识别              |
| 图片     | ✔    | 语法如 `![alt](url)`          |
| 表格     | ✔    | 表头需有分隔线 `---`          |
| LaTeX    | ✔    | 块级 `$$...$$` 与行内 `$...$` |

### 渲染流程图：

```
+--------------+
| append(delta)|
+--------------+
       |
       v
提取完整 Markdown 块（段落/图片/表格）
       |
       v
入队：Channel<String> 渲染队列
       |
       v
渲染并动画展示（主线程）
```

---

## 三、`AndroidManifest.xml` — 应用与权限配置

- 声明所需权限：

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

- 设置应用主入口：

```xml
<activity
    android:name=".MainActivity"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.MAIN" />
        <category android:name="android.intent.category.LAUNCHER" />
    </intent-filter>
</activity>
```

---

## 环境与依赖

- 最低 SDK：建议 API 24+
- 网络库：`OkHttp`
- Markdown 渲染：`Markwon` 及其插件
- 协程环境：推荐搭配 Jetpack Lifecycle (`lifecycleScope`)

Gradle 依赖示例：

```groovy
implementation 'io.noties.markwon:core:4.6.2'
implementation 'io.noties.markwon:image-coil:4.6.2'
implementation 'io.noties.markwon:ext-latex:4.6.2'
implementation 'io.noties.markwon:html:4.6.2'
implementation 'io.noties.markwon:ext-tables:4.6.2'
implementation 'io.noties.markwon:inline-parser:4.6.2'
```

---

## 使用说明

1. 获取 Qwen API Key，并替换请求头中的密钥：
   ```kotlin
   .addHeader("Authorization", "Bearer sk-你的密钥")
   ```

2. 调用 `streamQwenResponse(prompt)` 获取增量文本

3. 将文本传入 `StreamMarkdownRenderer.append()` 实现渲染

---

## 适用场景

- AI 聊天助手
- 富文本生成工具（含数学、图像）
- 教育类内容解释系统

---

## License

仅供学习参考，使用 Qwen API 请遵守其官方使用条款。

---
