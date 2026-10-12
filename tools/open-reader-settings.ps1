# ═══════════════════════════════════════════════════════════════
#  发送【菜单键】到 Android TV 模拟器，用于打开「阅读设置」面板
#
#  【为什么需要这个】
#  Android TV 模拟器的 keylayout 里【没有】任何按键映射到
#  KEYCODE_MENU / KEYCODE_SETTINGS，所以键盘上按不出这两个键
#  （手机上常用的 Ctrl+M 在 TV 镜像里也不起作用）。
#  唯一可靠的办法是用 adb 注入按键事件。
#
#  【为什么要用 PowerShell 而不是纯 bat】
#  cmd.exe 读取 .bat 文件用的是系统 OEM 代码页（中文 Windows 上是 GBK），
#  而文件是 UTF-8，中文注释会被解码成乱码并当成命令执行。
#  PowerShell 原生支持 UTF-8，所以中文放在 .ps1 里最安全。
#  外层那个 .bat 只负责调用它并 pause（纯 ASCII，不会乱码）。
#
#  【真机上没这个问题】
#  大多数 Android TV 遥控器上都有三条横线的菜单键，按下去就能用。
#  这个脚本只是为了让开发调试方便。
# ═══════════════════════════════════════════════════════════════

$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'

Write-Host ''
Write-Host '  ══════════════════════════════════════════' -ForegroundColor DarkCyan
Write-Host '   发送【菜单键】到 Android TV 模拟器' -ForegroundColor Cyan
Write-Host '  ══════════════════════════════════════════' -ForegroundColor DarkCyan
Write-Host ''

if (-not (Test-Path $adb)) {
    Write-Host "  ✗ 找不到 adb：" -ForegroundColor Red
    Write-Host "    $adb" -ForegroundColor Red
    Write-Host ''
    Write-Host '    如果你的 SDK 装在别的位置，请修改本脚本第一行的路径。' -ForegroundColor Yellow
    exit 1
}

# 先确认真的有设备连着，否则 adb 会卡住或报一堆看不懂的错
$state = (& $adb get-state 2>&1 | Out-String).Trim()
if ($state -notmatch 'device') {
    Write-Host '  ✗ 没有检测到已连接的设备/模拟器。' -ForegroundColor Red
    Write-Host ''
    Write-Host '    请先在 Android Studio 里把 TV 模拟器启动起来，' -ForegroundColor Yellow
    Write-Host '    然后再双击本脚本。' -ForegroundColor Yellow
    exit 1
}

& $adb shell input keyevent 82 | Out-Null

if ($LASTEXITCODE -ne 0) {
    Write-Host '  ✗ 发送按键失败。' -ForegroundColor Red
    exit 1
}

Write-Host '  ✓ 菜单键已发送。' -ForegroundColor Green
Write-Host ''
Write-Host '    现在切到模拟器窗口看：' -ForegroundColor Gray
Write-Host '    · 如果 App 停在【阅读页】 → 设置面板应该已经弹出来了' -ForegroundColor Gray
Write-Host '    · 如果 App 停在【书架】或导入页 → 不会有反应，这是正常的' -ForegroundColor Gray
Write-Host '      （只有阅读页才响应设置键）' -ForegroundColor DarkGray
Write-Host ''
