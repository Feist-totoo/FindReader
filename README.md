# 觅览阅读器 (FindReader) · Android

<div align="center">
  <img src="art/阅读Logo_透明Logo.png" width="128" height="128" alt="FindReader Logo" />
  <h3>纯粹 · 优雅 · 沉浸的原生 Android 小说阅读器</h3>
  <p>纯 Kotlin 原生实现（非 WebView 包装）· 独创 ReadingAnchor 定位算法 · 5 款艺术沉浸书架</p>

  <p>
    <img src="https://img.shields.io/badge/Platform-Android_7.0+_(API_24+)-brightgreen.svg" alt="Platform" />
    <img src="https://img.shields.io/badge/Kotlin-1.9+-blue.svg" alt="Kotlin" />
    <img src="https://img.shields.io/badge/Target_SDK-34-orange.svg" alt="Target SDK" />
    <img src="https://img.shields.io/badge/Privacy-100%25_Offline-success.svg" alt="Privacy" />
  </p>
</div>

---

## 核心特性 (Features)

### 1. 独创 ReadingAnchor 锚定算法
> **痛点**：传统阅读器在调大字号、调整间距或翻转屏幕后，常常“找不到刚刚读到哪里”。

- **前三行基准锚定**：在调节字号（12 档）、行间距（5 档）、段间距（小/中/大）、页边距（窄/标准/宽）、切换字体或更换阅读背景时，严格以当前可视画面**最上面三行文字**计算锚点偏移量，秒级复原视线焦点。
- **无感恢复**：进度精确记录到章节、章内比例与 `charOffset`，重启或后台切回 100% 还原阅读位置。

### 2. 三大阅读与翻页模式
- **上下滚动**：章节无缝衔接，前后动态加载/释放文本块，内存占用平稳极低。
- **横向分页（从左往右）**：经典现代电子书滑动体验，平滑手势跟手。
- **古籍排版（从右往左）**：日漫与古籍翻阅习惯，右滑/点左侧翻至下一页。

### 3. 五款艺术沉浸书架
书架不仅仅是列表，更是一间属于自己的精神居所：

| 书架风格 | 视觉与交互设计 |
|:---|:---|
| **🪵 古朴 · 藏書閣** | 红木书柜、两层七卷陈列；翻架时书柜优雅转身，古典宁静。 |
| **📁 简约 · 档案馆** | 未选中的书斜插在底部收纳槽中，选中书目平滑升至中央，如调取卷宗。 |
| **🏛️ 收藏馆 · 展覽廳** | 第一人称 360° 透视环视展柜；天顶聚光灯射下，展品自转，双向阻尼回弹。 |
| **☁️ 云端 · 云书房** | 书籍卧于云间，随着翻架天色按 `白日 → 黄昏 → 夜晚 → 清晨` 实时昼夜流转。 |
| **🌌 星海 · 星图** | 类似 Star Walk 的天球仰望视角，环顾 30°–70° 天球环带；内置 100 套预置七星艺术星座，每颗星对应一本藏书，满载微光流移。 |

### 4. 纯粹高效的文本检索与解析引擎
- **记事本式查找栏**：顶部呼出极简检索条，跨章节循环查找，匹配词黄色高亮并平滑定位跳转。
- **智能编码探测与毫秒级索引**：自动识别 `UTF-8`、`UTF-16`、`GB18030`、`GBK` 等编码格式，提取零误差章节字节偏移缓存（`files/cache/<id>.json`），数十兆大部头秒开不卡顿。
- **EPUB 原生解析**：解构 container → OPF → spine/nav 体系，解析元数据与章节文本。

### 5. 主题与 DIY 皮肤拓展
- 内置 9 种精修阅读主题：纸白、米黄、青草绿、樱花粉、高级灰、羊皮纸、沙丘、星空夜幕等。
- **自定义主题皮肤包（.zip）**：支持导入自定义皮肤压缩包（内含 `theme.json` 色板、`bg.png` 阅读底图、`bar.png` 顶底栏贴图及专属 `font.ttf` 字体）。

### 6. 100% 本地优先与隐私安全
- **零网络权限**：不申请 `INTERNET` 权限，不收集任何日志或用户信息，所有书目与进度均完整保存在设备本地沙盒中。

---

## 工程目录结构

```
FindReader/
├── app/src/main/
│   ├── java/com/quark/reader/
│   │   ├── MainActivity.kt        # 书架主控（古朴 / 简约 / 收藏馆模式）
│   │   ├── ReaderActivity.kt      # 阅读器主控（菜单、记事本查找、翻页调度）
│   │   ├── ScrollReaderView.kt    # 上下连续滚动阅读视图（支持 ReadingAnchor 锚定）
│   │   ├── PagedView.kt           # 左右分页阅读视图（支持 LTR / RTL 双向翻页）
│   │   ├── Books.kt               # TXT 编码探测、精确章节索引缓存(v2)、全文查找
│   │   ├── Prefs.kt               # SharedPreferences 进度与设置存储
│   │   ├── ThemeSpec.kt           # 内置 9 套配色规范与 ZIP 皮肤解析器
│   │   ├── Ui.kt                  # ReadingAnchor 算法 / 标题加粗 / 段落间距 Span
│   │   ├── CloudShelfView.kt      # 云端书架与昼夜流转光影
│   │   ├── GalaxyShelfView.kt     # 星海星座书籍卡片与导航
│   │   ├── HemisphereSkyView.kt   # 天球投影与三维视角漫游
│   │   ├── ConstellationField.kt  # 30°–70° 环带星座球面距离与防重叠排布
│   │   └── BookSearchPanel.kt     # 书架书名检索面板
│   ├── assets/
│   │   ├── constellations.json    # 100 款预存艺术星座点阵数据
│   │   └── welcome.txt            # 内置新手说明书（以书本形式呈现）
│   └── res/                       # 原生布局、全套矢量/位图资源与主题样式
├── art/                           # 高清设计原图、透明 Logo 与各尺寸图标
├── build.gradle.kts               # 项目根构建脚本
├── settings.gradle.kts            # 项目模块配置
└── gradle/                        # Gradle Wrapper
```

---

## 编译与运行 (Build & Run)

### 前置要求
- **JDK 17**
- **Android SDK**（API 34, Build Tools 34.0.0）
- **Android Studio** Hedgehog (2023.1.1) 或更新版本

### 命令行编译
```bash
# 克隆仓库
git clone git@github.com:Feist-totoo/FindReader.git
cd FindReader

# 运行单元测试
./gradlew testDebugUnitTest

# 编译 Debug APK
./gradlew assembleDebug

# 生成的 APK 位于：app/build/outputs/apk/debug/app-debug.apk
```
