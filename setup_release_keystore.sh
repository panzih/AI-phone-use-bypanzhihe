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

if [ -e "$KEYSTORE" ] || [ -e "$PROPS" ]; then
    echo "❗ 已存在 $KEYSTORE 或 $PROPS，我没有覆盖现有签名材料。"
    echo "   先核对当前签名配置与备份；更换密钥会让已安装版本无法升级。"
    exit 1
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

# 自动生成 24 个随机字节的十六进制口令（48 个字符）。
# 输入是有限长度，避免 `tr </dev/urandom | head -c 24` 在 pipefail 下因
# tr 收到 SIGPIPE 而返回 141、让整个脚本在建密钥前退出。
PASS="$(od -An -N24 -tx1 /dev/urandom | tr -d '[:space:]')"

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

⚠️  口令保存在 $PROPS 中；请另存到密码管理器并保护好这个文件。
    密钥文件也请备份到别的地方 —— 丢了就永远无法给老用户推送升级。

下一步：

    ./gradlew assembleRelease
    # 产物：app/build/outputs/apk/release/app-release.apk

EOF
