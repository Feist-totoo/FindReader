# QuarkReader 交接文档

> 复刻夸克浏览器「小说阅读模式」的独立本地阅读器。双端交付：PC Web 版（原型/参照实现）+ Android 原生版（正式产物）。
> 交接日期：2026-10-26 · 工程目录：`c711af51-7e76-48ed-a28a-9bbd86f684a8/`

---

## 1. 交付物清单

| 交付物 | 位置 | 说明 |
|---|---|---|
| **Android APK（正式产物）** | `QuarkReader/QuarkReader-debug.apk` | 12.8MB，debug 签名，minSdk 24 / targetSdk 34，包名 `com.quark.reader`，应用名「小说阅读」 |
| Android 源码工程 | `QuarkReader/` | Kotlin + Gradle，可直接用 Android Studio 打开 |
| PC Web 版 | `quark-reader-pc/` | 原型与视觉参照实现，浏览器运行 |
| PC 版说明 | `quark-reader-pc/README.md` | 运行方式与功能清单 |
| Android 版说明 | `QuarkReader/README.md` | 功能与构建说明 |
| 启动图标源文件 | `QuarkReader/logo-tmp/` | 用户提供的 logo（PNG 原图 1254px / 应用图标 1024px / 透明版 / ico） |

**未打包进交付物**：《玄鉴仙族》样本已按要求移除；应用内改为《欢迎使用·小说阅读》说明书（assets/welcome.txt）。

---

## 2. Android 端（正式产物）

### 2.1 功能总览

**阅读器**
- 双阅读模式：**上下连续滚动**（章节无缝衔接、前后动态增删章节块、滚动位置补偿）与**翻页**（覆盖/仿真/无，StaticLayout 行级分页、拖拽跟手、章末自动进退章）
- 夸克同款 UI：元信息块（书名/作者/来源/网址）、章节大标题、2 字符缩进正文、底部时间+电量+进度%
- 底部菜单：上一章｜进度胶囊（拖动跳转，胶囊实时显示百分比）｜下一章 + 目录/夜间/设置
- 目录抽屉：当前章高亮、正序/倒序
- 设置面板：亮度（映射系统背光）+护眼、字号 12 档（A⁻/A⁺）、9 种阅读主题（纸白/米黄/草绿/樱粉/高级灰/羊皮纸/沙丘/星空夜幕/夜间）、翻页方式+间距、自动阅读、更多设置项
- 更多设置：音量键翻页、点击两侧翻页、显示通知栏、进度范围（全本/按章）、展示进度时间电量、屏保常亮
- 夜间模式、自动阅读（0.5x–5x，滚动模式连续滚动/翻页模式定时翻页）、自定义字体（ttf/otf 导入）

**书架（5 种艺术风格，悬浮岛切换）**
- 古朴·藏書閣：立体木柜框架+红底金字匾额+封面竖排古籍靠放（每架 6 本=两层×3）+翻架动效+案台文房四宝矢量剪影（砚台/毛笔/墨锭/线装书）+阅读进度角标，支持左右横滑切架
- 简约·档案馆：顶部 ARCHIVE 标记与卷册计数，中心对焦卡（封面/书名/作者/阅读进度/翻阅按钮，点击直接开书），底部倾斜收纳槽插卡（dp 级精确展开位移），支持跟手拖拽与横滑切书
- 收藏馆·展覽廳：地坪反光+暗场聚光灯束+光晕+基座+玻璃罩高光+双重金边黄铜铭牌，展品转身切换（到端即停），支持横滑切展品
- 云端·云书房：天空蓝渐变背景+云朵载书横穿画面，出界后随机方向/高度/速度再入，书名带发光阴影
- 星河·星海：严格对齐 PC 原型。深空紫蓝双重星云弥散背景+13 个真实星座（发光连线+亮暗双态微星）；下方转轴支持 1:1 跟手直接旋转（彻底解决 atan2 漂移问题）+松手最近星座磁吸；点击点亮星座进入放大聚焦模式（展示连线与发光星点，点击星点开书，左上方「‹ 星座」返回）
- 悬浮玻璃岛：随 5 种风格实时变换材质底色、描边、文字与加号按钮强调色，告别刺眼白条割裂感
- 长按书籍：自定义封面 / 详情 / 移除

**数据与缓存（性能核心）**
- TXT 流式导入：ContentResolver 流读取 → BOM/UTF-8 校验/GBK 兜底 → 逐块写盘 + 同步建章节索引
- 章节索引缓存：`files/cache/<id>.json`（标题/字节偏移/长度），重开**秒开**（RandomAccessFile 按章随机读，不整本加载）
- 位置恢复：进度（章号+章内比例）存 SharedPreferences，每次打开自动回到上次退出位置
- EPUB：container.xml→OPF→spine/nav 解析，抽取章节文本与元数据
- 内置《欢迎使用》说明书：书架为空时自动导入（走同一管线，作为一本书展示全部功能）

### 2.2 源码结构

```
QuarkReader/app/src/main/java/com/quark/reader/
├── Prefs.kt              单例偏好：全部设置项、每本书进度(prog_<id>)、书单/字体清单(JSON)
├── ThemeSpec.kt          9 种阅读主题色板(ThemeSpec) + 书架主题定义
├── Books.kt              核心：流式导入(streamImport)/编码探测/EPUB 解析/
│                         索引缓存(Handle.reloadIndex)/按章随机读(chapterText)/进度
├── Ui.kt                 自绘控件：BatteryIcon 电池、PillBar 进度胶囊(拖动+百分比)
├── ScrollReaderView.kt   连续滚动视图：章节块增删/trim(≤16章)/定位锁/比例计算
├── PagedView.kt          分页视图：StaticLayout 分页/拖拽翻页/章界回调
├── ReaderActivity.kt     阅读器主控：菜单/抽屉/设置面板/更多设置/自动阅读/音量键/生命周期存档
└── MainActivity.kt       书架 + 5 风格 View（Antique/Minimal/Gallery/Cloud/GalaxyShelfView）
```

资源：`res/layout/`（阅读页/设置面板/目录项）、`res/drawable/`（图标+背景 shape，含 ic_add）、
`res/mipmap-*/ic_launcher_legacy.png`（用户 logo 生成的五档密度图标，清单直用 PNG，未启用自适应图标）。

### 2.3 构建与发布

```bash
# 依赖：JDK 17 + Android SDK（platforms;android-34、build-tools;34.0.0）
cd QuarkReader
set JAVA_HOME=<JDK17>
<gradle>/bin/gradle.bat assembleDebug        # 产物 app/build/outputs/apk/debug/app-debug.apk
```

- 本机已验证的组合：JDK17（`~/.gradle/jdks/eclipse_adoptium-17-amd64-windows.2`）+ Gradle 8.2.1（`~/.gradle/wrapper/dists/gradle-8.2.1-all/.../bin/gradle.bat`，wrapper 生成因网络校验失败，直接用完整发行版构建）
- `local.properties` 已写死本机 SDK 路径，换机器需修改
- **发布正式版**：把 `buildTypes.release` 配上签名（keystore），跑 `assembleRelease`；当前只有 debug 签名，安装时系统会提示"未知来源"
- 升级版本号：`app/build.gradle.kts` 的 `versionCode/versionName`

### 2.4 已知限制 / 待办

- [ ] 书架 5 风格动效较 PC 版精简（星星闪烁、光锥呼吸、玻璃罩反光等未做）
- [ ] 分页模式的进度恢复按近似页计算（ratio×3）
- [ ] 阅读器设置面板中"系统字体/背景/间距"打开的是子面板但未做返回动画
- [ ] 星河风格在手机端转动灵敏度与吸附体验待调优
- [ ] EPUB 仅抽文本，未保留图片/排版样式
- [ ] 无正式签名 keystore；`Books.io` 为单线程执行器，超大 EPUB 导入时 UI 无进度条（仅 Toast）

---

## 3. PC Web 版（原型/参照）

### 3.1 运行

```bash
cd quark-reader-pc
python -m http.server 8899 --bind 127.0.0.1   # 打开 http://127.0.0.1:8899/
```

必须走 HTTP（fetch 限制）。手机窗口模式：浏览器窗口 <500px 自动进入全屏手机布局。

### 3.2 结构

```
quark-reader-pc/
├── index.html        书架屏 + 阅读屏 + 全部面板（版本号 ?v=N 破缓存，改代码后记得 +1）
├── css/style.css     阅读器样式（主题 CSS 变量/菜单/抽屉/设置面板）
├── css/shelf.css     书架 5 风格完整样式（古朴木柜/简约档案柜/收藏馆展厅/云端/星河）
├── js/db.js          IndexedDB（books/fonts 两个 store）
├── js/epub.js        EPUB 解析（JSZip，本地 lib/jszip.min.js）
├── js/shelf.js       5 风格渲染器 + TXT 流式导入 + 封面/长按管理
├── js/app.js         阅读器核心（连续滚动引擎/分页动画/交互/字体）
├── sample.txt 等     测试样本（不入包）
```

### 3.3 与 Android 版的对应关系

| PC（HTML/CSS/JS） | Android（Kotlin） |
|---|---|
| css 变量主题 `--s-*` | `ThemeSpec` 色板 |
| js/shelf.js 渲染器 `Renderers.*` | `*ShelfView` 自定义 View |
| js/app.js 连续滚动（scrollRange/trimRange/定位锁） | `ScrollReaderView`（range/trimRange/positioning 同逻辑） |
| IndexedDB books/fonts | `files/books|cache|covers|fonts` 目录 + Prefs JSON |
| FontFace 注册自定义字体 | `Typeface.createFromFile` |

PC 版是视觉与交互的**参照标准**；Android 版如需对齐某个动效，先看 PC 版对应渲染器实现。

---

## 4. 关键设计决策（避免翻案）

1. **样本不入包**：曾打包 19MB《玄鉴仙族》，已移除；改为欢迎说明书走同一导入管线（用户确认）
2. **章节连续**：所有模式下章节内容连续呈现——滚动模式同流衔接，翻页模式章末无缝进退章（用户明确要求，勿改回单章切换）
3. **跳转定位**：程序化定位一律瞬时（禁用平滑动画）+ 定位锁挂起 range 调整，否则平滑动画与动态增删章节互相打架导致位置错误（已踩坑）
4. **浏览器缓存**：PC 版资源引用带 `?v=N`，改任何 js/css 后必须递增版本号，否则手机/浏览器吃旧缓存（已踩坑）
5. **后台 rAF 陷阱**：浏览器窗格失焦时 requestAnimationFrame 暂停，云端流动/星空自转已改为**纯 CSS 动画**实现，勿改回 rAF
6. **收藏馆不循环**：到左右两端即停（简约风格允许循环，用户指定）
7. **星河转轴在屏幕下方**、转动时星座文字隐藏、定位完成后一旁淡入；星座角距/半径带哈希抖动（自由布局）
8. **对比度**：所有浮层文字必须显式设色——全局主题改为 Light 后仍有若干自定义面板，新增 UI 时注意深底配浅字、浅底配深字

## 5. 测试样本

- `quark-reader-pc/test-book1/2/3.txt`：三本生成的测试书（科幻/武侠/都市，各 4-6 章）
- `quark-reader-pc/test.epub`：3 章 EPUB（含 nav 目录、元数据）
- `quark-reader-pc/fonts/test-font.ttf`：仿宋字体（测自定义字体导入）
- Android 端验证路径：安装 APK → 书架出现《欢迎使用》→ 点右上「＋」导入上述任一文件 → 各风格/阅读功能实测

## 6. 联络上下文

- 全部需求演进与踩坑记录在本会话；PC 版先于 Android 版开发，Android 版为其原生移植
- 用户偏好：直接执行不问确认、重视视觉细节（多轮截图评审迭代）、中文沟通
