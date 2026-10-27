# 从这里开始：给继续开发的 AI

更新日期：2026-10-27。

这是已有的 **Kotlin 原生 Android 阅读器**，用户会把本工程交给网页版 AI 继续完善。“网页版 AI”指接收源码的助手，程序本身仍是 Android 应用。请先读本文，再读 README.md、VALIDATION.md 和源码，依据用户后续要求继续修改。

## 当前工程

- 应用名：小说阅读；包名 `com.quark.reader`。
- minSdk 24、targetSdk/compileSdk 34，JDK 17，Gradle 8.2.1，Android Gradle Plugin 8.2.1，Kotlin 1.9.22。
- Kotlin + Android View/Canvas + XML，非 Compose，非 WebView。没有云端后端；“云端”是书架视觉风格名称。
- 支持本地 TXT/EPUB、导入、自定义封面、阅读设置、**自定义阅读主题与自定义书架风格压缩包（`.zip`）**、正文关键词查找、书籍管理及阅读进度保存。
- 内置五种书架：古朴、简约、收藏馆、云端、星海；**支持通过 `.zip` 皮肤包无限添加自定义新书架风格**（如听雨阁、绯樱庭、全息舱等）。
- 配套自定义主题 & 书架风格 MCP Server：`mcp-theme-server/quark_theme_mcp.py`（支持 JSON-RPC 2.0 over stdio 与 CLI 模式，附带 3 套生成的全能阅读+书架示例皮肤包 `mcp-theme-server/sample-skins/*.zip`）。

## 用户已明确的需求与最新实现（2026-10-27）

### 1. 阅读器文本界面与交互升级

1. **修复文本界面间距设置问题**：
   - 将段间距（`paraSp`：小 `0.4f` / 中 `0.9f` / 大 `1.4f`）通过 `ParagraphSpacingSpan`（`LineHeightSpan`）真正接入 `ScrollReaderView` 与 `PagedView` 的段落排版，使行间距、段间距、页边距三项均实时生效。
   - 修复子面板 `chipsRow2` 点击后立即刷新当前选中项的高亮状态。
2. **删除亮度和护眼设置**：
   - 从 `view_settings.xml`、`activity_reader.xml`、`ReaderActivity.kt` 和 `Prefs.kt` 中彻底移除亮度进度条、护眼开关与橙色护眼遮罩层（`eyeOverlay`）。
3. **翻页模式替换选项为“上下滚动，从左往右，从右往左”**：
   - 设置面板翻页方式行替换为：`上下滚动 (scroll)`、`从左往右 (ltr)`、`从右往左 (rtl)`，旧版配置值自动平滑迁移为 `ltr`。
   - `PagedView` 完整支持 `ltr`（点击右侧/向左滑下一页）与 `rtl`（点击左侧/向右滑下一页）的手势、点击翻页与拖拽阴影方向。
4. **以画面最上面三行作为基准定位（解决字号/行间距变化迷失位置 & 切换背景内容不一致）**：
   - 新增 `ReadingAnchor(chapterIndex, rawCharOffset, topThreeOffsets)` 与 `ChapterTextFormatter` 双向字符偏移映射表。
   - 无论在滚动模式还是分页模式下，切换字号、行间距、段间距、页边距、字体、背景颜色/自定义皮肤或翻页模式时，均先提取画面最上面三行文字在章节原始文本中的精确字符偏移，重排后严格恢复到该行位置，杜绝比例取整漂移或跳回章首。
5. **修复按章节跳转不准确**：
   - 修复 `Books.kt` 中 TXT 流式导入时章节起始字节偏移量（`curStart`）与实际写出字节不一致导致跨章/跳章内容错位的根因，统一写入规范化 UTF-8 章节数据并记录精确字节偏移与长度（`INDEX_VERSION = 2`，旧缓存自动平滑重建）。
   - 修复 `ReaderActivity` 与 `ScrollReaderView` 中跳章时同步调用未完成布局 View（`blk.top == 0`）及按钮重复调用 `jumpChapter` + `prevChapter` 的问题。
6. **修复部分章节标题没加粗加大**：
   - 扩展 `Books.CH_RE` 章节识别正则（覆盖“第X章/节/回/卷/集/部/篇/季/话/幕”、数字序号标题、“序章/楔子/引子/尾声/终章/番外/后记”及最长 64 字标题），新增章内二级小标题识别 `SUB_HEADING_RE`。
   - 新增 `ChineseBoldSpan`（同时设置 `Typeface.BOLD` 与 `isFakeBoldText = true`）结合 `RelativeSizeSpan`，确保在所有中文字体与分页/滚动视图下章节主标题与章内小标题均显著加粗加大。
7. **右上角改为记事本式关键词查找**：
   - 移除阅读界面顶栏右上角原有的宫格菜单（`btnGrid` / `gridPop`），替换为放大镜查找按钮（`btnSearchText`）。
   - 点击展开轻量级记事本风格查找栏（`findBar`：仅包含关键词输入框、“查找下一个”按钮和关闭按钮，无替换功能），支持在全书范围内循环查找关键词、精准跳转到目标章节对应行并以醒目底色高亮当前匹配词。
8. **自定义阅读主题 + 自定义添加新书架风格（`.zip` 皮肤包）与配套 MCP Server**：
   - **阅读主题皮肤**：在 `ThemeSpec.kt` 中实现 `CustomThemeManager` 与 `SkinBackgroundDrawable`，支持导入解析 `.zip` 皮肤包（含 `theme.json`、`bg.png`、`bar.png`、`font.ttf`）。
   - **自定义新书架风格**：新增 `CustomShelfSpec` 与 `CustomShelfView.kt`，支持在书架顶部风格弹层点击 **「＋ 导入自定义书架风格（.zip）」** 添加全新的书架风格（点击切换、长按移除，悬浮岛标题自动截取前 3 个字以遵守简洁规范）。支持 6 种空间架构模板（`cabinet` 双层多宝阁、`carousel` 全息中央聚焦抽卡、`grid` 艺术画廊陈列墙、`gallery` 3D 展厅、`cloud` 云端、`galaxy` 星海）、自定义书架背景贴图 (`shelf_bg.png`)、搁板与案台贴图 (`board.png` / `desk.png`)、悬浮玻璃岛专属配色，以及 6 种实时氛围粒子特效（`none` / `petals` 落樱 / `fireflies` 流萤 / `snow` 飞雪 / `stars` 星尘 / `rain` 细雨）。支持全套联名包（同一个 `.zip` 同时注册新书架风格与配套阅读主题）。
   - **MCP Server**：在 `mcp-theme-server/quark_theme_mcp.py` 中实现标准 JSON-RPC 2.0 MCP Server，预生成 3 套包含阅读皮肤+全新书架风格的联名 `.zip` 压缩包（`竹林听雨·听雨阁`、`徽墨宣卷·绯樱庭`、`赛博霓虹·全息舱`）。

### 2. 书架内置五种风格既有约束（已完整保留）

- **各风格共用**：减少说明性文字，风格标题最多三个字；右上半透明搜索图标打开书名搜索并保留定位动画（自定义书架风格同样实现了 `ShelfNavigator.focusBook` 定位动画）；`SafeSystemBars` 统一适配安全区。
- **收藏馆**：第一人称摄像机 Yaw，展柜间隔 90°，角度范围 `[0, (N-1)×90°]`，两端弹性阻尼，不循环。
- **云端**：每架 7 本，架数为 `max(1, ceil(书籍数/7))`；循环的是天色（白日→黄昏→夜晚→清晨），不是书架。
- **星海**：Star Walk 形式，所有书籍星座完整范围位于仰角 30°–70° 环带；初始 7 个星座（49 本），填满后闪烁并慢速无规则游移，球面防重叠。
- **简约**：左右按钮支持点按切换与长按加速连续切换，松手/移出/失焦立即停止。

## 核心文件入口

| 文件（相对于 app/src/main/java/com/quark/reader/） | 职责 |
|---|---|
| MainActivity.kt | 书架主控、导入/管理入口、顶部控件、内置书架 View 及自定义书架风格导入/切换/移除 |
| CustomShelfView.kt | 自定义书架风格渲染引擎（支持 cabinet / carousel / grid / gallery / cloud / galaxy 架构与实时氛围粒子 `ShelfParticleView`） |
| GalleryCamera.kt | 收藏馆投影、角度边界、阻尼、侧光及释放吸附算法 |
| CloudShelfView.kt / CloudShelfPages.kt | 云端书架、天色循环、架数与定位 |
| GalaxyShelfView.kt | 星海书卡和返回导航 |
| HemisphereSkyView.kt | 天球透视、拖动环顾、星座绘制、搜索飞行 |
| ConstellationField.kt | 星座容量、球面几何、环带布局和防重叠运动 |
| BookSearchPanel.kt / ShelfSupport.kt | 搜索弹层、匹配、定位接口、系统安全区与动画偏好 |
| ReaderActivity.kt | 阅读菜单、主题/自定义皮肤导入、翻页模式、记事本式关键词查找、以顶部三行为基准的位置恢复 |
| ScrollReaderView.kt / PagedView.kt | 连续滚动和分页（从左往右/从右往左）排版引擎，支持 `ReadingAnchor` 顶部三行基准定位 |
| Books.kt / Prefs.kt | TXT/EPUB 导入、精确 UTF-8 章节索引（v2）、全文关键词查找、持久化 |
| ThemeSpec.kt | 内置色板 + `CustomShelfSpec` + `CustomThemeManager`（阅读主题与书架风格 ZIP 解析）+ `SkinBackgroundDrawable` |
| Ui.kt | `ReadingAnchor`、`ParagraphSpacingSpan`、`ChineseBoldSpan`、`ChapterTextFormatter`、电池与进度胶囊控件 |

自定义主题 & 书架风格 MCP Server：`mcp-theme-server/quark_theme_mcp.py` 及说明文档 `mcp-theme-server/README.md`。
