# 书架与阅读器优化验证记录

验证日期：2026-10-27。

## 一、2026-10-27 第二轮（阅读器专项修复与自定义皮肤 MCP）验证记录

- **源码静态审查与根因核验**：
  1. **文本界面间距设置**：确认原实现中 `Prefs.paraSp` 未传入 `ScrollReaderView` 与 `PagedView`，且 `chipsRow2` 点击后未重绘选中态。现已通过 `ParagraphSpacingSpan`（`LineHeightSpan`）将段间距独立注入每个段落尾部，点击选项后实时更新高亮并以顶部三行为基准重排。
  2. **删除亮度与护眼设置**：已从 `view_settings.xml`、`activity_reader.xml`、`ReaderActivity.kt`、`Prefs.kt` 中彻底移除亮度滑块、护眼开关与 `eyeOverlay` 遮罩。
  3. **翻页模式替换为“上下滚动，从左往右，从右往左”**：已替换设置面板选项为 `上下滚动 (scroll)`、`从左往右 (ltr)`、`从右往左 (rtl)`，并在 `PagedView` 中实现 `ltr`/`rtl` 的手势滑动方向、点击左右两侧翻页方向及拖拽阴影方向。
  4. **字体/行间距变化及背景切换以画面最上面三行作为基准**：实现 `ReadingAnchor` 与 `ChapterTextFormatter` 双向字符偏移映射，在字号、行距、段距、页边距、字体、背景颜色/皮肤、翻页模式切换时均锁定画面最上面三行文字对应的原始字符偏移，消除内容跳变与位置丢失。
  5. **按章节跳转精确到位**：修复 `Books.kt` 流式导入时章节字节偏移错位问题（升级为 `INDEX_VERSION = 2` 规范化章节字节写出，旧缓存自动重建），并修复 `ReaderActivity`/`ScrollReaderView` 跳章时未布局 `blk.top == 0` 及上一章/下一章按钮重复调用的问题。
  6. **章节标题加粗加大**：扩展 `Books.CH_RE` 覆盖“第X章/节/回/卷/集/部/篇/季/话/幕”、数字序号章节及“序章/楔子/番外/后记”等，新增章内二级小标题识别 `SUB_HEADING_RE`，并使用 `ChineseBoldSpan`（`Typeface.BOLD` + `isFakeBoldText = true`）与 `RelativeSizeSpan(1.26f / 1.18f)` 确保所有章节标题显著加粗加大。
  7. **右上角改为记事本式关键词查找**：移除顶栏原宫格菜单，改为查找图标按钮，点击展开包含输入框、“查找下一个”按钮和关闭按钮的查找栏，支持跨章节循环查找、精确跳转与匹配词高亮。
  8. **自定义主题皮肤包（.zip）与 MCP Server**：完成 `CustomThemeManager` 与 `SkinBackgroundDrawable`，并在 `mcp-theme-server/quark_theme_mcp.py` 实现标准 JSON-RPC 2.0 MCP Server，预生成并通过校验 3 套示例皮肤包（`QuarkReader-Skin-bamboo_mist.zip`、`QuarkReader-Skin-ink_scroll.zip`、`QuarkReader-Skin-cyber_neon.zip`）。
- **新增单元测试**：新增 `app/src/test/java/com/quark/reader/ReaderFeaturesTest.kt`（覆盖翻页模式映射、章节与副标题正则、TXT 章节字节偏移精确性、顶部三行锚点分页映射、自定义主题 ZIP 解压与色板解析）。
- **构建环境说明**：当前云端沙盒环境无外网连接且未预装 Android SDK (`android.jar`) 与 `kotlinc`，因此本次在沙盒内完成了 Python MCP Server 的端到端协议与皮肤包生成测试，Android 工程已完成全部 Kotlin/XML 源码与单元测试编写；在本地安装了 JDK 17 + Android SDK 34 的机器上直接运行 `./gradlew testDebugUnitTest assembleDebug` 即可编译新 APK。

## 二、既有书架优化验证记录

- 100 个离线星座模板数量、唯一性、点坐标范围与连通性已核验。
- 单元测试覆盖初始 7 个星座、49/50/56/57 本的扩容边界、700 本以上书籍映射、100 个星座持续运动及静态障碍的球面防重叠约束。另验证 7–700 个星座布局的完整角范围均在 30°–70° 仰角环带内，跨 0°/360° 接缝防重叠及相机最短转向。
- 云端首次展示白日及第 1–7 本书；1 本时仅 1 架，50 本时 8 架；第 5 架白日、第 6 架黄昏，末架再滑不回首页。49 本展示 7 个星座，50 本展示 8 个星座。
