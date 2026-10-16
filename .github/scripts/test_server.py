"""Emülatör testi için yerel ses sayfası: emülatör bu sunucuya 10.0.2.2:8000 üzerinden ulaşır.
YouTube'a bağlı olmadan MP3'e çevirme, kapak, etiket ve kitaplık zincirini dener."""
import http.server
import math
import os
import struct
import sys
import wave
import zlib

root = sys.argv[1]
os.makedirs(root, exist_ok=True)

# 6 saniyelik 440 Hz ton
with wave.open(os.path.join(root, "song.wav"), "wb") as w:
    w.setnchannels(1)
    w.setsampwidth(2)
    w.setframerate(22050)
    w.writeframes(b"".join(struct.pack("<h", int(12000 * math.sin(2 * math.pi * 440 * i / 22050))) for i in range(22050 * 6)))

# 160x90 mavi PNG kapak (16:9, uygulama kare kırpmalı)
width, height = 160, 90
raw = b"".join(b"\x00" + bytes((20, 70 + y, 216)) * width for y in range(height))
def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
png = b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)) + chunk(b"IDAT", zlib.compress(raw)) + chunk(b"IEND", b"")
open(os.path.join(root, "cover.png"), "wb").write(png)

open(os.path.join(root, "page.html"), "w", encoding="utf-8").write("""<html><head><meta charset="utf-8">
<title>EFK Test - Deneme Şarkısı (Official Video)</title>
<meta property="og:title" content="EFK Test - Deneme Şarkısı (Official Video)">
<meta property="og:image" content="http://10.0.2.2:8000/cover.png">
</head><body><audio src="http://10.0.2.2:8000/song.wav" controls></audio></body></html>""")

os.chdir(root)
http.server.ThreadingHTTPServer(("0.0.0.0", 8000), http.server.SimpleHTTPRequestHandler).serve_forever()
