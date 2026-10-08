#!/system/bin/sh
# ============================================================
# getslide.sh — 从任意 cheese 日志提取当前 boot 的 KASLR slide
# 搜索位置（全考虑）: 显式参数 > $CHEESE_LOG > 常见路径 > 任意 *.log
# 解析字段: leak rfu / tp0 funcs walk fail / it / kvo / vr: kbase
# 交叉投票 + 2MB 对齐校验；输出 KBASE 与 SLIDE
# 用法:  sh getslide.sh [额外log路径...]
# ============================================================
say(){ echo "$*"; }

# ---- 1. 收集日志文件 ----
SEARCH="/data/local/tmp/cheese.log /data/local/tmp2/cheese.log \
/sdcard/Download/cheese.log /sdcard/cheese.log /data/local/cheese.log"
[ -n "$CHEESE_LOG" ] && SEARCH="$CHEESE_LOG $SEARCH"
[ $# -gt 0 ] && SEARCH="$* $SEARCH"
FOUND=""
for f in $SEARCH; do [ -f "$f" ] && FOUND="$FOUND $f"; done
# 兜底：常见目录里的任意 .log（取最新的）
if [ -z "$FOUND" ]; then
  for d in /data/local/tmp /data/local/tmp2 /sdcard/Download /sdcard; do
    for f in "$d"/*.log; do
      [ -f "$f" ] && FOUND="$FOUND $f"
    done 2>/dev/null
  done
fi
[ -z "$FOUND" ] && { echo "NO_LOG_FOUND"; echo "(pass log path as arg1 or set CHEESE_LOG)"; exit 2; }

# ---- 2. 逐文件解析候选 kbase ----
CAND=""   # space-separated 候选（每个=十六进制）
SRC=""
for f in $FOUND; do
  LEAK=$(grep -aoE 'leak: kvo=[0-9a-f]+ it=[0-9a-f]+ rf=[0-9a-f]+ rfu=[0-9a-f]+' "$f" 2>/dev/null | tail -1)
  RFU=$(echo "$LEAK" | sed -n 's/.*rfu=\([0-9a-f]*\).*/\1/p')
  IT=$(echo  "$LEAK" | sed -n 's/.*it=\([0-9a-f]*\).*/\1/p')
  KVO=$(echo "$LEAK" | sed -n 's/.*kvo=\([0-9a-f]*\).*/\1/p')
  # 旧 cheese: tp0 funcs walk fail 行的值是 unregfunc 运行时
  [ -z "$RFU" ] && RFU=$(grep -aoE 'tp0 funcs walk fail \([0-9a-f]+\)' "$f" 2>/dev/null | tail -1 | sed -n 's/.*fail (\([0-9a-f]*\)).*/\1/p')
  VK=$(grep -aoE 'vr: kbase=[0-9a-f]+' "$f" 2>/dev/null | tail -1 | cut -d= -f2)
  if [ -n "$RFU" ] && [ "$RFU" != "0" ]; then
    KB=$(printf '%x' $((16#$RFU - 0x1ef70c)))
    [ $((16#$KB % 0x200000)) -eq 0 ] && { CAND="$CAND $KB"; SRC="$SRC rfu($f)"; }
  fi
  if [ -n "$IT" ] && [ "$IT" != "0" ]; then
    IT2=$(echo "$IT" | sed 's/^0*//')
    [ -n "$IT2" ] && KB=$(printf '%x' $((16#$IT2 - 0x01b078)))
    [ $((16#$KB % 0x200000)) -eq 0 ] && { CAND="$CAND $KB"; SRC="$SRC it($f)"; }
  fi
  if [ -n "$KVO" ] && [ "$KVO" != "0" ]; then
    KB=$(printf '%x' $((16#$KVO + 0xA8000000)))
    [ $((16#$KB % 0x200000)) -eq 0 ] && { CAND="$CAND $KB"; SRC="$SRC kvo($f)"; }
  fi
  if [ -n "$VK" ] && [ "$VK" != "0" ] && [ $((16#$VK % 0x200000)) -eq 0 ]; then
    KB=$(printf '%x' $((16#$VK + 0xA8000000)))
    [ $((16#$KB % 0x200000)) -eq 0 ] && { CAND="$CAND $KB"; SRC="$SRC vr($f)"; }
  fi
done

# ---- 3. 交叉投票 ----
[ -z "$CAND" ] && { echo "NO_KBASE_IN_LOGS"; echo "(logs found: $FOUND)"; exit 3; }
BEST=""; BESTN=0
for c in $CAND; do
  n=0
  for d in $CAND; do [ "$d" = "$c" ] && n=$((n+1)); done
  if [ $n -gt $BESTN ]; then BESTN=$n; BEST=$c; fi
done
SLIDE=$(printf '%x' $((16#$BEST - 16#ffffffc008000000)))
case "$SLIDE" in 0|0000000000000000|"") echo "SLIDE_INVALID"; exit 4;; esac

# ---- 4. 输出 ----
echo "LOGS=$FOUND"
echo "KBASE=0x$BEST (votes=$BESTN, sources:$SRC)"
echo "SLIDE=0x$SLIDE"
# made by tadaki
