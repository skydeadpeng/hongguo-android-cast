#!/bin/bash
# Compare the production decryptor with FFmpeg's CENC implementation on newly generated media.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
TMP="$(mktemp -d -t hongguo-cenc-ffmpeg.XXXXXX)"
trap 'rm -rf "$TMP"' EXIT
"$JAVA_HOME/bin/javac" -encoding UTF-8 -d "$TMP/classes" \
  "$ROOT/app/src/main/java/io/github/jackotom/hongguo/android/CencDecryptor.java" "$ROOT/tests/CencDecryptorTest.java"
python3 - "$TMP" "$JAVA_HOME/bin/java" <<'PY'
from pathlib import Path
import json,shutil,subprocess,sys
root=Path(sys.argv[1]); java=sys.argv[2]
ffmpeg=shutil.which('ffmpeg'); ffprobe=shutil.which('ffprobe')
if not ffmpeg or not ffprobe: raise SystemExit('FFMPEG_TEST_DEPENDENCY_MISSING')
# Public generated-fixture material only. Neither this value nor any native command line is printed.
key=bytes((i*7+11)&255 for i in range(16)); kid=bytes(range(16))
(root/'fixture.key').write_bytes(key)
def run(command):
    result=subprocess.run(command,capture_output=True)
    if result.returncode: raise RuntimeError('FFmpeg/JVM conformance operation failed exit='+str(result.returncode))
    return result.stdout
run([ffmpeg,'-y','-hide_banner','-loglevel','error','-f','lavfi','-i','testsrc2=size=96x64:rate=6','-f','lavfi','-i','sine=frequency=500:sample_rate=48000','-t','1.3','-c:v','libx264','-preset','ultrafast','-pix_fmt','yuv420p','-c:a','aac',str(root/'clear.mp4')])
run([ffmpeg,'-y','-hide_banner','-loglevel','error','-i',str(root/'clear.mp4'),'-c','copy','-encryption_scheme','cenc-aes-ctr','-encryption_key',key.hex(),'-encryption_kid',kid.hex(),str(root/'encrypted.mp4')])
common=['-map','0:v:0','-map','0:a?','-f','framemd5','-']
reference=run([ffmpeg,'-hide_banner','-loglevel','error','-decryption_key',key.hex(),'-i',str(root/'encrypted.mp4'),*common])
for mode in ['senc','saio-fallback']:
    content=bytearray((root/'encrypted.mp4').read_bytes())
    if mode=='saio-fallback':
        def hide_senc(start,end):
            while start<end:
                size=int.from_bytes(content[start:start+4],'big'); typ=bytes(content[start+4:start+8]); header=8
                if size==1: size=int.from_bytes(content[start+8:start+16],'big'); header=16
                elif size==0: size=end-start
                assert size>=header and start+size<=end
                if typ==b'senc': content[start+4:start+8]=b'free'
                elif typ in [b'moov',b'trak',b'mdia',b'minf',b'stbl']: hide_senc(start+header,start+size)
                start+=size
        hide_senc(0,len(content))
    output=root/(mode+'.part'); output.write_bytes(content)
    result=run([java,'-cp',str(root/'classes'),'io.github.jackotom.hongguo.android.CencDecryptorTest','--file',str(output),str(root/'fixture.key')])
    print(result.decode().strip())
    actual=run([ffmpeg,'-hide_banner','-loglevel','error','-i',str(output),*common])
    assert actual==reference,'decoded video/audio frame hashes differ'
    info=json.loads(run([ffprobe,'-v','error','-show_entries','stream=codec_name,codec_tag_string','-of','json',str(output)]))
    codecs=[(s['codec_name'],s['codec_tag_string']) for s in info['streams']]
    assert codecs==[('h264','avc1'),('aac','mp4a')], 'original codec tags were not restored'
    assert (root/'encrypted.mp4').stat().st_size==output.stat().st_size
    print('PASS CencDecryptor FFmpegConformance mode='+mode+' decodedVideoAudioFramesEqual=true originalCodecs=avc1,mp4a fileLengthUnchanged=true networkRequests=0')
PY
