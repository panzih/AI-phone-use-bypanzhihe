# -*- mode: python ; coding: utf-8 -*-
"""macapp.spec — PyInstaller 打包配置。

用法：
    pyinstaller macapp.spec --noconfirm

产物：dist/AI手机助手.app

几个关键设置的理由：
  console=False    图形界面应用不能带终端窗口，否则双击后一直挂着一个黑框
  BUNDLE           生成标准 macOS .app 目录结构
  config.json 打包进去  这样 .app 自带一份配置模板，首次启动时
                      paths.config_path() 会把它复制到用户数据目录，
                      用户不用另外去下载配置文件
  excludes         排掉用不到的大块头，能把体积压小一些
"""
import os

block_cipher = None

# 需要一起打包进去的只读资源。
# config.json 是配置模板；文档也带上，方便用户在 .app 里查看。
datas = [('config.json', '.')]
for extra in ('README.md', 'DESIGN.md'):
    if os.path.exists(extra):
        datas.append((extra, '.'))

# 用不到的库，显式排除以缩小体积。
# 注意不要排除 tkinter / urllib / ssl —— 那是程序真正依赖的。
excludes = [
    'numpy', 'scipy', 'pandas', 'matplotlib', 'IPython', 'jupyter',
    'pytest', 'setuptools', 'pip', 'wheel',
    'PyQt5', 'PyQt6', 'PySide2', 'PySide6', 'wx',
    'email', 'html', 'http.server', 'xmlrpc',
]

a = Analysis(
    ['macapp.py'],
    # 项目目录要显式加进来：macapp.py 里有一堆 import adb / agent / config
    # 这样的同级模块，打包时 PyInstaller 得能找到它们。
    # 注意：spec 文件里没有 __file__，要用 PyInstaller 提供的 SPECPATH。
    pathex=[SPECPATH],
    binaries=[],
    datas=datas,
    hiddenimports=[
        # 这几个模块是运行时才 import 的（在函数体内部），
        # PyInstaller 的静态分析可能漏掉，所以显式声明。
        'adb', 'agent', 'config', 'image', 'llm', 'tools', 'paths',
        'main', 'run_logger',
    ],
    hookspath=[],
    hooksconfig={},
    runtime_hooks=[],
    excludes=excludes,
    win_no_prefer_redirects=False,
    win_private_assemblies=False,
    cipher=block_cipher,
    noarchive=False,
)

pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)

exe = EXE(
    pyz,
    a.scripts,
    [],
    exclude_binaries=True,
    name='AI手机助手',
    debug=False,
    bootloader_ignore_signals=False,
    strip=False,
    upx=False,
    console=False,          # GUI 应用：不要终端窗口
    disable_windowed_traceback=False,
    argv_emulation=False,   # 不需要接收拖拽到图标上的文件
    target_arch=None,
    codesign_identity=None,
    entitlements_file=None,
)

coll = COLLECT(
    exe,
    a.binaries,
    a.zipfiles,
    a.datas,
    strip=False,
    upx=False,
    upx_exclude=[],
    name='AI手机助手',
)

app = BUNDLE(
    coll,
    name='AI手机助手.app',
    icon=None,              # 没有图标文件，用系统默认
    bundle_identifier='com.local.aiphoneassistant',
    info_plist={
        'CFBundleName': 'AI手机助手',
        'CFBundleDisplayName': 'AI手机助手',
        'CFBundleShortVersionString': '0.1.0',
        'CFBundleVersion': '0.1.0',
        'NSHighResolutionCapable': True,
        # 说明为什么需要这些权限（macOS 会在首次运行时提示）
        'NSAppleEventsUsageDescription':
            '用于打开配置文件和日志目录。',
        'LSMinimumSystemVersion': '10.13',
    },
)
