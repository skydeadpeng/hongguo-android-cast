#!/usr/bin/env python3
"""Bind production phone-layout methods to deterministic view geometry doubles, not an emulator."""
from pathlib import Path
import re, sys

src, output = Path(sys.argv[1]), Path(sys.argv[2])
main = (src / 'MainActivity.java').read_text()
player = (src / 'PlayerActivity.java').read_text()

def method(source, name):
    found = re.search(r'(?m)^    (?:@Override )?(?:private|public|protected) [^\n{]*\b' + re.escape(name) + r'\([^;\n]*\)\s*\{', source)
    if not found:
        return None
    start, cursor = found.start(), source.index('{', found.start())
    depth, quote, escaped, comment = 0, None, False, None
    while cursor < len(source):
        char, pair = source[cursor], source[cursor:cursor + 2]
        if comment == 'line':
            if char == '\n': comment = None
        elif comment == 'block':
            if pair == '*/': comment = None; cursor += 1
        elif quote:
            if escaped: escaped = False
            elif char == '\\': escaped = True
            elif char == quote: quote = None
        elif pair == '//': comment = 'line'; cursor += 1
        elif pair == '/*': comment = 'block'; cursor += 1
        elif char in ('"', "'"): quote = char
        elif char == '{': depth += 1
        elif char == '}':
            depth -= 1
            if depth == 0: return source[start:cursor + 1].replace('private ', '', 1)
        cursor += 1
    raise SystemExit('unclosed actual method: ' + name)

modern = method(main, 'applyWebInsets') is not None and method(player, 'applyPhoneLayout') is not None
phone_has_cast = bool(re.search(r'episodeActions\s*=\s*BuildConfig\.IS_TV\s*\?\s*new Button\[\]\{[^}]*\}\s*:\s*new Button\[\]\{[^}]*\bcastButton\b', player))
main_methods = method(main, 'applyWebInsets')
if main_methods is None:
    # The 0.9 baseline has this statement inside its real WindowInsets listener.
    statement = re.search(r'view\.setPadding\(bars\.left, bars\.top, bars\.right, bars\.bottom\);', main)
    if not statement: raise SystemExit('missing baseline production insets statement')
    body = statement.group().replace('view.', 'container.').replace('bars.', '')
    main_methods = 'void applyWebInsets(FrameLayout container, int left, int top, int right, int bottom, int imeBottom) {' + body + '}'
main_methods += '\n' + (method(main, 'onConfigurationChanged') or '')
text = '''package io.github.jackotom.hongguo.android;
final class MainLayoutHarness extends LayoutActivityBase {
    FrameLayout webContainer = new FrameLayout(this);
    int mediaReloads;
    MAIN_METHODS
}
'''.replace('MAIN_METHODS', main_methods)
if modern:
    methods = '\n'.join(method(player, name) or '' for name in ['dp', 'setPhoneSafeArea', 'applyPhoneLayout', 'addPhoneButton', 'onConfigurationChanged'])
    text += '''
final class PlayerLayoutHarness extends LayoutActivityBase {
    FrameLayout playerRoot = new FrameLayout(this);
    LinearLayout top = new LinearLayout(this), bottom = new LinearLayout(this), heading = new LinearLayout(this);
    LinearLayout phoneContent = new LinearLayout(this), phoneActions = new LinearLayout(this), phoneOptions = new LinearLayout(this);
    ScrollView phoneScroller = new ScrollView(this);
    TextView title = new TextView(this), status = new TextView(this), time = new TextView(this);
    Button back = new Button(this), qualityButton = new Button(this), speedButton = new Button(this), danmakuButton = new Button(this);
    final boolean phoneHasCast = PHONE_HAS_CAST;
    Button[] episodeActions = new Button[BuildConfig.IS_TV || !phoneHasCast ? 5 : 6];
    int safeLeft, safeTop, safeRight, safeBottom;
    int generation = 9, position = 47000, mediaReloads;
    boolean userPaused = true, autoplay = true;
    String quality = "1080p", actualQuality = "720p";
    float requestedSpeed = 1.5f;
    final Object video = new Object();
    PlayerLayoutHarness() {
        playerRoot.width=360; playerRoot.height=800;
        back.text="返回"; title.text="一部较长的短剧 · 第 16 集"; status.text="首选 1080p · 当前 720p · 实际 720×1280";
        top.addView(back, new LinearLayout.LayoutParams(86,48));
        heading.setOrientation(LinearLayout.VERTICAL); heading.addView(title);heading.addView(status);
        top.addView(heading,new LinearLayout.LayoutParams(0,-2,1));
        phoneContent.setOrientation(LinearLayout.VERTICAL); phoneContent.addView(time);
        View seek=new View(this); seek.minimumHeight=48; phoneContent.addView(seek,new LinearLayout.LayoutParams(-1,48));
        phoneContent.addView(phoneActions); phoneContent.addView(phoneOptions);
        qualityButton.text="清晰度 1080p · 当前 720p"; speedButton.text="倍速 1.5×"; danmakuButton.text="弹幕 读取中";
        String[] labels={"上一集","暂停","下一集","选集","连播 开","投屏"};
        for(int i=0;i<episodeActions.length;i++){episodeActions[i]=new Button(this);episodeActions[i].text=labels[i];}
        phoneScroller.addView(phoneContent); bottom.addView(phoneScroller);
        playerRoot.addView(top); playerRoot.addView(bottom);
    }
    void loadAt(int a,String b,int c){mediaReloads++;}
    void buildViews(){mediaReloads++;}
    void saveProgress(){mediaReloads++;}
    PLAYER_METHODS
}
'''.replace('PLAYER_METHODS', methods).replace('PHONE_HAS_CAST', str(phone_has_cast).lower())
output.write_text(text)
print('MOBILE_LAYOUT_BINDING actualMethods=true modern=' + str(modern).lower() + ' baselineInlineInsets=' + str(not modern).lower())
