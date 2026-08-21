#!/system/bin/sh
# 把 9 个 v213_split_0~8 + 本脚本 一起放到 /sdcard/Download
# MT管理器→终端→  sh v213_安卓合并.sh
cd "$(dirname "$0")"
cat v213_split_0 v213_split_1 v213_split_2 v213_split_3 v213_split_4 v213_split_5 v213_split_6 v213_split_7 v213_split_8 > JiPaiQi-NativeYolo-debug-v2.1.3.apk
echo "--- done. md5 checksum ---"
md5sum JiPaiQi-NativeYolo-debug-v2.1.3.apk
echo "期望 MD5: 6ced3eef8664fa218a6a7b9bd88c863e"
ls -lh JiPaiQi-NativeYolo-debug-v2.1.3.apk
