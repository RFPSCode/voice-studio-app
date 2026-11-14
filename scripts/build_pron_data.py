#!/usr/bin/env python3
"""Build android/app/src/main/assets/pron.json — data for pronunciation fixes.

Sources (fetched by the caller, see scripts/build_pron_data.sh):
  OpenCC TSCharacters.txt / TSPhrases.txt  (Apache-2.0)      traditional -> simplified
  libchewing-data tsi.csv (新酷音詞庫, LGPL-2.1)                 Taiwan readings with usage frequency
  moedict dict-revised.json (教育部《重編國語辭典修訂本》, CC BY-ND 3.0 TW)  cross-check of Taiwan readings
  pypinyin + jieba (MIT)                                       mainland readings, character frequency
Only small derived tables (readings that differ, one common character per syllable) are written.
"""
import json, re, sys, unicodedata, collections
from pypinyin import pinyin, Style, pinyin_dict, phrases_dict
import jieba, os
jieba.initialize()

opencc_dir, chewing_csv, moedict_json, out = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]

def tone3(syl):
    """'xīng' -> 'xing1', 'ma' -> 'ma5', handles ü"""
    s = unicodedata.normalize('NFD', syl.strip().lower())
    tone = 5
    for ch, t in (('̄', 1), ('́', 2), ('̌', 3), ('̀', 4)):
        if ch in s: tone = t
    s = re.sub(r'[̀-ͯ]', lambda m: '̈' if m.group(0) == '̈' else '', s)
    s = unicodedata.normalize('NFC', s).replace('ü', 'v').replace('ü', 'v')
    s = re.sub(r'[^a-zv]', '', s)
    return s + str(tone) if s else ''

# ---------- OpenCC
ts, tsp = {}, {}
for name, dst in (('TSCharacters.txt', ts), ('TSPhrases.txt', tsp)):
    for line in open(os.path.join(opencc_dir, name), encoding='utf-8'):
        if '\t' not in line: continue
        k, v = line.rstrip('\n').split('\t', 1)
        dst[k] = v.split(' ')[0]

# 著: as a particle (走著、開著) mainland text writes 着 (zhe); 著 stays only in words read zhù
ts['著'] = '着'
for w in ['著名', '著作', '顯著', '土著', '著述', '著稱', '名著', '原著', '巨著', '著者', '論著', '編著', '專著', '卓著', '昭著', '著書', '著錄', '遺著', '新著', '譯著']:
    tsp[w] = ''.join(ts.get(c, c) if c != '著' else '著' for c in w)

def t2s(s):
    out, i = [], 0
    while i < len(s):
        for L in range(min(6, len(s) - i), 1, -1):
            w = s[i:i + L]
            if w in tsp: out.append(tsp[w]); i += L; break
        else:
            out.append(ts.get(s[i], s[i])); i += 1
    return ''.join(out)

# ---------- character frequency (mainland text)
freq = collections.Counter()
for line in open(os.path.join(os.path.dirname(jieba.__file__), 'dict.txt'), encoding='utf-8'):
    w, f = line.split()[:2]
    for ch in w: freq[ch] += int(f)

def readings(ch):
    r = pinyin_dict.pinyin_dict.get(ord(ch))
    return [tone3(x) for x in r.split(',')] if r else []

# ---------- one common, unambiguous character per syllable (mainland reading)
py2ch = {}
cands = collections.defaultdict(list)
for cp, r in pinyin_dict.pinyin_dict.items():
    ch = chr(cp)
    if not ('一' <= ch <= '鿿') or freq[ch] < 50: continue
    rs = [tone3(x) for x in r.split(',')]
    mono = len(set(rs)) == 1
    try: g = ch.encode('gb2312')
    except UnicodeEncodeError: continue
    level = 1 if g[0] < 0xD8 else 2                      # GB2312 level 1 = the 3755 most common characters
    # common characters only; a character with a single reading is strongly preferred
    cands[rs[0]].append((level, -(freq[ch] * (1.0 if mono else 0.02)), ch))
for syl, lst in cands.items():
    lst.sort()
    py2ch[syl] = lst[0][2]
# first (most common) mainland reading of common characters: an annotated character that already reads right is kept
r1 = {}
for cp, r in pinyin_dict.pinyin_dict.items():
    ch = chr(cp)
    if '\u4e00' <= ch <= '\u9fff' and freq[ch] >= 50:
        r1[ch] = tone3(r.split(',')[0])

# ---------- zhuyin <-> pinyin
from dragonmapper import transcriptions as T
zy2py = {}
for syl in py2ch:
    base, tone = syl[:-1], int(syl[-1])
    num = base.replace('v', 'ü') + str(tone)
    try:
        z = T.pinyin_to_zhuyin(T.numbered_to_accented(num) if tone != 5 else base.replace('v', 'ü'))
    except Exception:
        continue
    z = z.replace(' ', '')
    if tone == 5 or '˙' in z: z = '˙' + z.replace('˙', '')     # neutral tone stored as a prefix: ˙ㄓㄜ
    zy2py[z] = syl

# ---------- Taiwan readings that differ from mainland ones
# Taiwan readings: libchewing (新酷音) word list — the most frequent reading of each word, i.e. how people in
# Taiwan actually say it today (it follows 《國語一字多音審訂表》 for common words: 操場 ㄘㄠ ㄔㄤˇ, 星期 ㄒㄧㄥ ㄑㄧˊ).
def zy_norm(z):
    z = z.strip()
    if '˙' in z: z = '˙' + z.replace('˙', '')
    return z
def zy_to_py(z):
    z = zy_norm(z)
    if z in zy2py: return zy2py[z]
    if z.startswith('˙') and z[1:] in zy2py: return zy2py[z[1:]][:-1] + '5'
    return None
readings_tw = collections.defaultdict(collections.Counter)
for line in open(chewing_csv, encoding='utf-8'):
    if line.startswith('#'): continue
    parts = line.rstrip('\n').split(',')
    if len(parts) < 3: continue
    w, f, zy = parts[0], int(parts[1] or 0), parts[2]
    if not w or not all('一' <= c <= '鿿' for c in w): continue
    readings_tw[w][zy] += f
def same(a, b):
    if a == b: return True
    if a[:-1] != b[:-1]: return False
    return a[-1] == '5' or b[-1] == '5'          # neutral-tone differences do not matter for the model
# Cross-check with 教育部《重編國語辭典修訂本》 (moedict): a reading is used only when the ministry's dictionary lists it
# for that word (for a single character: it must be the character's only reading there).
moe = {}
for e in json.load(open(moedict_json, encoding='utf-8')):
    t = e.get('title', '')
    if 1 <= len(t) <= 4:
        moe[t] = [re.sub(r'\s+', ' ', h['bopomofo'].replace('　', ' ')).strip() for h in e.get('heteronyms', []) if h.get('bopomofo')]
def moe_ok(w, zy):
    lst = moe.get(w)
    if not lst: return False
    zy = ' '.join(zy_norm(x) for x in zy.split(' '))
    lst = [' '.join(zy_norm(x) for x in m.split(' ')) for m in lst]
    return (lst == [zy]) if len(w) == 1 else (zy in lst)
tw, tw_zy = {}, {}
for w, cnt in readings_tw.items():
    if not (1 <= len(w) <= 4): continue
    total = sum(cnt.values())
    zy, top = cnt.most_common(1)[0]
    if total <= 0 or top < 0.85 * total: continue                 # Taiwan usage itself is split
    syl = zy.split(' ')
    py = [zy_to_py(x) for x in syl]
    if len(py) != len(w) or None in py: continue
    s = t2s(w)
    if len(w) == 1:
        rs = set(readings(s)) | set(readings(w))
        if len(rs) != 1 or freq[s] < 2000 or total < 200: continue  # only characters with one mainland reading
        mainland = [rs.pop()]
    else:
        if jieba.dt.FREQ.get(s, 0) < 20 and total < 50: continue
        mainland = [p[0] for p in pinyin(s, style=Style.TONE3, neutral_tone_with_five=True)]
        mainland = [m if m[-1].isdigit() else m + '5' for m in mainland]
    if len(mainland) != len(w): continue
    # 一、不、七、八 change tone by context (tone sandhi); the model does that by itself
    diff = [i for i in range(len(w)) if w[i] not in '一不七八' and not same(py[i], mainland[i])]
    if not diff or not moe_ok(w, zy): continue
    rep = list(s)
    ok = True
    for i in diff:
        c = py2ch.get(py[i])
        if not c: ok = False; break
        rep[i] = c
    if not ok: continue
    if ''.join(rep) == s: continue
    tw[w] = ''.join(rep)
    tw_zy[w] = zy

# hand-checked additions / removals
EXTRA = {'姊': 'ㄐㄧㄝˇ'}                    # 學姊、姊姊：mainland reads zǐ
SKIP_CHARS = set('癌')                        # 癌：Taiwan schools now teach ㄞˊ, older dictionaries ㄧㄢˊ
for w, zy in EXTRA.items():
    py = [zy_to_py(x) for x in zy.split(' ')]
    tw[w] = ''.join(py2ch[x] for x in py); tw_zy[w] = zy
for w in [w for w in tw if any(c in SKIP_CHARS for c in w)]:
    del tw[w]; tw_zy.pop(w, None)

# polyphonic characters worth a listen (shown as a hint, never changed automatically)
POLY = '長重行還樂教數種曲調強處更藏差參量少空背分應興降角塞薄削給似朝將假省率盛轉覺奔'
data_poly = ''.join(c for c in POLY if len(set(readings(t2s(c)))) > 1)

# drop multi-char entries that the single-character table already handles the same way
for w in list(tw):
    if len(w) > 1:
        r = ''.join(tw.get(c, t2s(c)) for c in w)
        if r == tw[w]: del tw[w]; tw_zy.pop(w, None)

data = {
    'version': 1,
    'note': 'Generated by scripts/build_pron_data.py. Taiwan readings from libchewing-data (LGPL-2.1); '
            'mainland readings from pypinyin; traditional->simplified from OpenCC (Apache-2.0).',
    'ts': ts, 'tsp': tsp, 'py2ch': py2ch, 'zy2py': zy2py,
    'tw': tw, 'twZhuyin': tw_zy, 'poly': data_poly, 'r1': r1,
}
json.dump(data, open(out, 'w', encoding='utf-8'), ensure_ascii=False, separators=(',', ':'))
# the same data for the PC editor (loaded with <script src>, works when the page is opened from disk)
pc = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'pc', 'pron-data.js')
with open(pc, 'w', encoding='utf-8') as f:
    f.write('// generated by scripts/build_pron_data.py — do not edit\nwindow.PRON=' + json.dumps(data, ensure_ascii=False, separators=(',', ':')) + ';\n')
print('ts', len(ts), 'tsp', len(tsp), 'py2ch', len(py2ch), 'zy2py', len(zy2py), 'tw', len(tw), file=sys.stderr)
