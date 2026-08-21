#!/system/bin/sh
# 把 11 个 v21_split_00~10 和本脚本一起放到「下载」目录 ( /sdcard/Download 或者 MT管理器任意目录)
# 用 MT管理器「终端」点执行，或者用 Termux 执行：bash v21_安卓合并.sh
cd "$(dirname "$0")"
cat v21_split_00 v21_split_01 v21_split_02 v21_split_03 v21_split_04 v21_split_05 v21_split_06 v21_split_07 v21_split_08 v21_split_09 v21_split_10 > JiPaiQi-DouZero-debug-v2.1.apk
echo "--- 合并完成，校验 MD5 ---"
md5sum JiPaiQi-DouZero-debug-v2.1.apk
echo " 正确= c3b50012b4e258af835c73f54af078f1"
ls -lh JiPaiQi-DouZero-debug-v2.1.apk
