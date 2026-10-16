#!/usr/bin/env bash
# Emülatörde uçtan uca deneme: uygulamayı aç, YouTube linkini paylaş menüsüyle
# indir, kitaplıkta çal, arama yap; ekran görüntüsü ve log topla.
set -u
OUT=smoke
PKG=com.efkstudio.music
APK=app/build/outputs/apk/debug/app-x86_64-debug.apk
TEST_URL="https://www.youtube.com/watch?v=jNQXAC9IVRw"
mkdir -p "$OUT"

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
ev() { adb shell "am start -n $PKG/.MainActivity --es efk_eval '$1'" > /dev/null 2>&1; }
state() { ev "console.log(\"EFK_STATE_$1 \"+JSON.stringify(JSON.parse(EFKNative.getState()).$2).slice(0,3000))"; }

adb install -r -g "$APK"
adb logcat -c
adb shell am start -n "$PKG/.MainActivity"
sleep 50
shot 01-home
state engine engine

adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "$TEST_URL" -n "$PKG/.MainActivity"
for i in $(seq 1 15); do
  sleep 10
  state "dl$i" downloads
  if adb logcat -d | grep -q '"status":"done"\|"status":"error"'; then break; fi
done
shot 02-downloads

ev "document.querySelector(\".tab[data-tab=library]\").click()"
sleep 4
shot 03-library
state library library

ev "var r=document.querySelector(\"#lib-list .row\"); if(r) r.click()"
sleep 4
ev "efk.onEvent(\"navigate\",{tab:\"player\"})"
sleep 6
shot 04-player
state player player

ev "efk.back(); document.querySelector(\".tab[data-tab=search]\").click(); setTimeout(function(){var q=document.querySelector(\"#q\"); q.value=\"lofi hip hop\"; q.form.requestSubmit();},800)"
sleep 45
shot 05-search
state search downloads

adb shell 'ls -la "/sdcard/Music/EFK Music/"' > "$OUT/music-dir.txt" 2>&1
adb logcat -d > "$OUT/logcat-full.txt"
echo "===== Müzik klasörü ====="
cat "$OUT/music-dir.txt"
echo "===== Önemli log satırları ====="
grep -E "EFK_STATE|AndroidRuntime|FATAL|CONSOLE|youtubedl|YoutubeDL" "$OUT/logcat-full.txt" | cut -c1-1500 | tail -150
exit 0
