#!/usr/bin/env bash
#
# 生成发布签名密钥。
#
# 这个脚本只跑一次，跑完之后：
#   keystore/release.keystore   密钥（gitignore，**必须自己备份**）
#   keystore.properties         口令（gitignore）
#
# 然后 `./gradlew assembleRelease` 出来的就是签名过的包。
#
# ⚠️ 密钥丢了就永久失去给老用户升级的能力 —— 安卓要求升级包的签名
#    和已装包一致，换了密钥所有人只能卸载重装。所以生成之后请把
#    keystore/ 整个目录复制到网盘 / 密码管理器里存好。

set -euo pipefail

cd "$(dirname "$0")"

KEYSTORE="keystore/release.keystore"
PROPS="keystore.properties"

if [ -f "$KEYSTORE" ]; then
    echo "❗ $KEYSTORE 已经存在，我没有覆盖它。"
    echo "   要重新生成的话先手动删掉（但那会让已发布的版本无法升级）。"
    exit 0
fi

if ! command -v keytool >/dev/null 2>&1; then
    echo "❗ 找不到 keytool。它是 JDK 自带的，装个 JDK 17 或者用 Android Studio 自带的也行："
    echo "   /Applications/Android Studio.app/Contents/jbr/Contents/Home/bin/keytool"
    exit 1
fi

read -r -p "密钥别名（随便起，记住就行）[zhihe]: " ALIAS
ALIAS="${ALIAS:-zhihe}"

read -r -p "署名（写你名字或组织名）: " CN
if [ -z "$CN" ]; then
    echo "❗ 署名不能为空"
    exit 1
fi

# 自动生成一个高强度口令。用你自己的想法也行，但别用弱口令 ——
# 这个口令是保护"只有你能发这个应用的升级包"这件事的。
PASS="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 24)"

mkdir -p keystore

keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias "$ALIAS" \
    -keyalg RSA \
    -keysize 4096 \
    -validity 10000 \
    -storepass "$PASS" \
    -keypass "$PASS" \
    -dname "CN=$CN, O=$CN, C=CN"

cat > "$PROPS" <<EOF
# 由 setup_release_keystore.sh 生成，不要提交。
storeFile=$KEYSTORE
storePassword=$PASS
keyAlias=$ALIAS
keyPassword=$PASS
EOF

chmod 600 "$KEYSTORE" "$PROPS"

cat <<EOF

✅ 生成完成

   密钥：$KEYSTORE
   配置：$PROPS
   别名：$ALIAS
   口令：$PASS

⚠️  上面这个口令只显示这一次，请立刻抄到密码管理器里。
    密钥文件也请备份到别的地方 —— 丢了就永远无法给老用户推送升级。

下一步：

    ./gradlew assembleRelease
    # 产物：app/build/outputs/apk/release/app-release.apk

EOF
