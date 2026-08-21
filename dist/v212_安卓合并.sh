#!/system/bin/sh
# 把 9 个 v212_split_0~8 + 本脚本 一起放到 /sdcard/Download
# MT管理器→终端→  bash v212_安卓合并.sh
cd "$(dirname "$0")"
cat v212_split_0 v212_split_1 v212_split_2 v212_split_3 v212_split_4 v212_split_5 v212_split_6 v212_split_7 v212_split_8 > JiPaiQi-DouZero-debug-v2.1.2.apk
echo "--- done. md5 checksum ---"
md5sum JiPaiQi-DouZero-debug-v2.1.2.apk
echo "期望 MD5: a84b2eee00f9c92b596d1061f4541922"
ls -lh JiPaiQi-DouZero-debug-v2.1.2.apk
