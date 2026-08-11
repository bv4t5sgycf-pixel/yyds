# 水量分布识别 · 原生 Android 版（路线 C）零基础指南

> 这个 APP 用 **原生 Kotlin + OpenCV SDK** 重写，OpenCV 的识别引擎直接打进安装包，**装好即可离线使用，不再需要联网下载任何东西**（这正是放弃 PWA 的原因——PWA 要在运行时下载 13MB 引擎，车间弱网一抖就卡死）。

---

## 一、你只需要做一件事：把代码推到 GitHub，等它自动出安装包

你不需要装 Android Studio、不需要会编译。GitHub 的免费云端会自动帮你把代码变成 APK。

### 步骤 1：注册/登录 GitHub
打开 https://github.com ，用邮箱注册一个免费账号并登录。

### 步骤 2：新建仓库
1. 点右上角 **＋ → New repository**
2. Repository name 随便写，比如 `water-detect-android`
3. 选 **Public**（或 Private 都行）
4. 不要勾 "Add a README file"（勾了也没事）
5. 点 **Create repository**

### 步骤 3：把 `android` 文件夹里的所有内容上传
在新建好的仓库页面：
- 点 **add a file → Upload files**
- 把本地 `D:\微信小程序\水量分布视觉识别(DDY)\android\` 文件夹下的**所有文件和子文件夹**拖进去
- 拉到最下面，点 **Commit changes**

**⚠️ 极易踩的坑：不要上传 `android` 这个文件夹本身！**

正确结果：仓库根目录直接看到 `settings.gradle`、`build.gradle`、`app/`、`.github/` 这些。
错误结果：仓库根目录多出一个 `android/` 文件夹，里面才是 `settings.gradle` 等文件。

> 如果文件太多拖着麻烦，也可以装一个 GitHub Desktop（https://desktop.github.com ）用图形界面上传，但网页拖拽也能用。

**已经传错了（根目录多了一个 `android/` 文件夹）怎么办？**
最简单的修法是改 workflow 文件，让它进 `android/` 子目录再编译：
1. 仓库里打开 `.github/workflows/build.yml`
2. 点右上角编辑按钮（铅笔图标）
3. 把全部内容替换成本指南末尾「附录：子目录版 build.yml」
4. 点 **Commit changes**
5. 重新等 Actions 跑（这次会走 `android/` 子目录）

### 步骤 4：等自动构建（约 2~5 分钟）
1. 上传成功后，点仓库顶部的 **Actions** 标签页
2. 你会看到一个名为 **Build Debug APK** 的任务在跑（黄色转圈）
3. 等它变绿色 ✅（如果变红 ❌，说明构建出错——把红色报错截图发给我，我来修）

### 步骤 5：下载安装包
1. 点进那个绿色成功的构建记录
2. 页面底部 **Artifacts** 区域，点 **app-debug-apk** 下载
3. 下载得到 `app-debug.apk`

### 步骤 6：装到手机
1. 把 `app-debug.apk` 传到安卓手机（微信文件传输/USB/U盘都行）
2. 手机上点开它 → 如果提示"允许安装未知来源应用"，按提示允许
3. 安装完成，桌面出现「水量分布识别」图标

---

## 二、怎么用

1. 打开 APP → 第一步选**设备板型**（1008 / 1200 / 4000）
2. 点**拍照**（首次会请求相机权限，允许）或**从相册选择**一张设备照片
3. 自动检测四角（红点）。如果没贴红点/检测不准，直接**拖动四角圆点**修正
4. 开启「自动吸附」后，拖动时放大镜会实时扫描红点并锁定（绿圈）；**放大镜倍数**调到 4~8 更好对位；**双击**某角可回弹到自动检测位置
5. 点**开始分析水量** → 看到正视图、网格、水位折线、各管读数
6. 顶部**平滑度**可切换（默认「关闭」）；点**查看诊断图**可在「标注图/纯正视图」间切换

---

## 三、工程结构（给你或后续维护者参考）

```
android/
├── build.gradle / settings.gradle / gradle.properties   # Gradle 工程配置
├── .github/workflows/build.yml                            # 自动出 APK（GitHub Actions）
└── app/
    ├── build.gradle                                      # 含 bytedeco:opencv:4.10.0 + opencv-platform 依赖
    └── src/main/
        ├── AndroidManifest.xml
        ├── java/com/example/waterdetect/
        │   ├── MainActivity.kt          # 入口：选板型、拍照/选图、初始四角检测
        │   ├── ImageHolder.kt          # 跨页面传递大图
        │   ├── model/BoardConfig.kt    # 三板型标定
        │   ├── cv/OpenCvEngine.kt      # OpenCV 初始化 + Bitmap↔Mat
        │   ├── cv/WaterDetector.kt     # 水球检测/透视校正/平滑（翻译自 analyzer.js）
        │   ├── cv/CornerDetector.kt    # 红点四角检测（翻译自 corners.js）
        │   └── ui/
        │       ├── CaptureActivity.kt  # 四角修正页
        │       ├── ResultActivity.kt   # 结果页 + 平滑度
        │       └── CornerEditView.kt   # 自定义视图：放大镜实时扫红点吸附
        └── res/                        # 布局/颜色/主题/图标
```

---

## 四、算法与之前 PWA 的关系

检测算法（预处理 CLAHE+双边、绿球/亮度掩膜、按列水位、透视校正、平滑 None 插值+中值、四角红点检测）**逐函数翻译**自之前验证过的 `pwa/js/analyzer.js` 与 `pwa/js/corners.js`，标定参数（板型 mm / 满管高度 / 管数）保持一致。

**已实现：** 红点四角模式（完整）、放大镜实时吸附、坚定落点、平滑度重算、诊断图。
**暂未实现：** ArUco 角标模式（原生 OpenCV 4.10 自带 aruco 模块，后续若要更稳可加）。

---

## 五、重要说明

- **我这边无法本地编译验证**（沙箱没有 Android SDK），工程是按可编译结构尽力编写的；GitHub Actions 会做真实编译。若构建报具体错误，把 Actions 红色日志发我，我据此修正。
- 当前出的是 **debug 包**（自动签名，可直接装、可长期使用），无需上架商店，适合车间自用。
- 第一次打开 APP 时 OpenCV 引擎从安装包内加载（毫秒级），**全程不需要网络**。
- 真机实测重点看：四角红点自动检测准不准、放大镜吸附绿圈是否锁对、分析结果读数是否合理；这些参数（红点阈值、圆度、平滑）拿到真实照片后可能要微调，告诉我实测情况即可。

---

## 附录：当前 build.yml（已支持自动检测根目录或 android/ 子目录）

现在 workflow 会自动判断 `settings.gradle` 在根目录还是在 `android/` 子目录，所以无论上传时是「内容直接放根目录」还是「多套了一层 `android/` 文件夹」都能编译。如果你需要手动替换 `.github/workflows/build.yml`，用下面这段：

```yaml
name: Build Debug APK

on:
  push:
    branches: [ main, master ]
  workflow_dispatch:

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Detect project directory
        run: |
          if [ -f settings.gradle ]; then
            echo "PROJECT_DIR=." >> $GITHUB_ENV
          elif [ -f android/settings.gradle ]; then
            echo "PROJECT_DIR=android" >> $GITHUB_ENV
          else
            echo "No settings.gradle found at root or android/"
            exit 1
          fi

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'

      - name: Setup Gradle (auto-provides gradle 8.9, no local wrapper needed)
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.9

      - name: Build debug APK
        working-directory: ${{ env.PROJECT_DIR }}
        run: gradle :app:assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: app-debug-apk
          path: ${{ env.PROJECT_DIR }}/app/build/outputs/apk/debug/app-debug.apk
```
