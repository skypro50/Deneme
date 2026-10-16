#!/usr/bin/env bash
# Emülatörde uçtan uca deneme: uygulamayı aç, yerel test sayfasını ve gerçek bir
# YouTube linkini paylaş menüsüyle indir, kitaplıkta çal, arama yap; log topla.
set -u
OUT=smoke
PKG=com.efkstudio.music
APK=app/build/outputs/apk/debug/app-x86_64-debug.apk
LOCAL_URL="http://10.0.2.2:8000/page.html"
YT_URL="https://www.youtube.com/watch?v=jNQXAC9IVRw"
mkdir -p "$OUT"

python3 .github/scripts/test_server.py "$OUT/server" > "$OUT/server.log" 2>&1 &

shot() { adb exec-out screencap -p > "$OUT/$1.png"; }
ev() { adb shell "am start -n $PKG/.MainActivity --es efk_eval '$1'" > /dev/null 2>&1; }
state() { ev "console.log(\"EFK_STATE_$1 \"+JSON.stringify(JSON.parse(EFKNative.getState()).$2).slice(0,2500))"; }
share() { adb shell am start -a android.intent.action.SEND -t text/plain --es android.intent.extra.TEXT "$1" -n "$PKG/.MainActivity" > /dev/null; }
wait_job() {
  for i in $(seq 1 18); do
    sleep 10
    state "$1_$i" downloads
    sleep 2
    if ! adb logcat -d | grep "EFK_STATE_$1_$i" | grep -q '"status":"\(queued\|running\|converting\)"'; then break; fi
  done
}

adb install -r -g "$APK"
adb logcat -G 16M || true
adb logcat -c
adb logcat -b crash -c || true
adb shell am start -n "$PKG/.MainActivity"
sleep 50
shot 01-home
state engine engine

echo ">>> Yerel sayfa: MP3 dönüştürme zinciri"
share "$LOCAL_URL"
wait_job local
shot 02-local-download
state library_after_local library

echo ">>> YouTube"
share "$YT_URL"
wait_job youtube
shot 03-youtube-download

ev "document.querySelector(\".tab[data-tab=library]\").click()"
sleep 3
ev "var r=document.querySelector(\"#lib-list .row\"); if(r) r.click()"
sleep 6
state player player
ev "efk.onEvent(\"navigate\",{tab:\"player\"})"
sleep 3
shot 04-player

ev "efk.back(); document.querySelector(\".tab[data-tab=search]\").click(); setTimeout(function(){var q=document.querySelector(\"#q\"); q.value=\"lofi hip hop\"; q.form.requestSubmit();},800)"
sleep 45
ev "console.log(\"EFK_STATE_searchdom \"+document.querySelectorAll(\"#search-body .row\").length+\" | \"+document.querySelector(\"#search-body\").innerText.replace(/\\s+/g,\" \").slice(0,600))"
sleep 2
shot 05-search

adb shell 'ls -la "/sdcard/Music/EFK Music/"' > "$OUT/music-dir.txt" 2>&1
adb logcat -d > "$OUT/logcat-full.txt"
echo "===== Müzik klasörü ====="
cat "$OUT/music-dir.txt"
adb logcat -b crash -d > "$OUT/crash.txt" 2>&1
echo "===== Çökme kayıtları ====="
head -120 "$OUT/crash.txt"
echo "===== Uygulama süreçleri ====="
grep -E "ActivityManager.*(com\.efkstudio|Killing|died)|Process com\.efkstudio|libc +: Fatal|AwBrowserTerminator|lowmemorykiller" "$OUT/logcat-full.txt" | grep -v "avc:" | cut -c1-400 | tail -40
echo "===== Önemli log satırları ====="
grep -E "EFK_STATE|AndroidRuntime|FATAL| EFK *:" "$OUT/logcat-full.txt" | grep -v "avc:" | cut -c1-2500 | tail -80
exit 0
