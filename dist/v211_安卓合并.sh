#!/system/bin/sh
# 把 9 个 v211_split_0~8 + 本脚本一起放到 /sdcard/Download，执行：bash v211_安卓合并.sh
cd "$(dirname "$0")"
cat v211_split_0 v211_split_1 v211_split_2 v211_split_3 v211_split_4 v211_split_5 v211_split_6 v211_split_7 v211_split_8 > JiPaiQi-DouZero-debug-v2.1.1.apk
echo "--- 合并完成 ---"
md5sum JiPaiQi-DouZero-debug-v2.1.1.apk
echo "期望 MD5: d710797191b5f469542d858bb3d924ae"
ls -lh JiPaiQi-DouZero-debug-v2.1.1.apk
