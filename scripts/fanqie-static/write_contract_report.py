import argparse,pathlib,json
parser=argparse.ArgumentParser();parser.add_argument("work_dir",help="Directory containing dexdump outputs");args=parser.parse_args()
p=pathlib.Path(args.work_dir);m=json.loads((p/'model-contract-data.json').read_text());e=json.loads((p/'enum-contract-data.json').read_text())
s='''# Fanqie 7.4.3.32 static model contract

Read-only evidence from official APK, DEX classes9/classes16/classes17, Android SDK dexdump -a -d. No APK execution, network API call, authentication emulation, or product changes.

## Most important integration findings

- Main App `com.dragon.read.rpc.model.GetVideoModelRequest` is flat. Series SDK request is nested; they are not interchangeable.
- SDK detail request uses `series_id`; SDK model request uses `video_id`. Do not copy an album ID or series ID into video_id without preserving the ID selector.
- `VideoSeriesIdType`: VideoId=0, SeriesId=1, PostId=2. These are enum-defined namespaces, not proof of the namespace of a particular catalog ID.
- `VideoContentType` differs from `ContentType`. Main and series VideoContentType use ShortSeriesPlay=1, ShortPlay=7, Album=1003, MotionComic=1004, UnrealMotionComic=1007. Main ContentType instead describes user content categories (VideoSeriesPost=5).
- `video_model` is a String, not a native nested object, in both main VideoModelData and SDK SaaSVideoModelData. Main response additionally offers `main_url` and `backup_url` String fields. Static schema alone does not establish that these URLs are populated, playable, or authorized for a given item.
- Main response envelope is `code`, `message`, `data`; SDK adds `debug_info`, `log_id` and uses a different error enum. Main multi-model response is data.video_model_data (Map); SDK multi-model response data itself is Map<String,SaaSVideoModelData>.
- Main request deserialization defaults verified in constructor: object/enum/string fields null; booleans false; source integer 0. SDK GetVideoBizParam defaults similarly null/false/0. Do not interpret client defaults as required server values or infer the server's absent-value semantics.
- Every generated field listed below is descriptor optional=true. This means generated client decoding permits absence, not that a valid server request can omit every field.
- Authentication/token-related field NAMES are retained only as schema. No token/key values were collected or emulated. No endpoint auth or DRM bypass is recommended.

## Exact descriptor field sequences and JVM model types

'''
for k,v in m.items():
 if not v['wire_fields']:continue
 if '/GetVideo' not in k and '/MGetVideo' not in k and '/SaaSVideo' not in k and not k.endswith('/VideoModelData$a'):continue
 base=k[:-2];s+='### '+base+'\n\n'
 s+='Wire fields (case-sensitive, descriptor order): '+', '.join('`'+x['name']+'`' for x in v['wire_fields'])+'.\n\n'
 s+='Model fields/types: '+', '.join('`'+x['name']+': '+x['type']+'`' for x in m.get(base,{}).get('fields',[]))+'.\n\n'
 s+='Evidence: `'+str(pathlib.Path(v['evidence']).relative_to(p))+'`.\n\n'
s+='## Relevant enums\n\n'
for k,v in e.items():
 if k.startswith('readersaas') or 'VideoDetailSource' in k:continue
 s+='- '+k+': '+', '.join(str(x['name'])+'='+str(x['value']) for x in v['values'])+'\n'
s+='''
## Reproduction and limits

1. Extract classes9.dex and classes17.dex from fanqie-official.apk, using Python zipfile; classes16 was already extracted as fanqie-classes16.dex.
2. Run Android SDK build-tools/35.0.0/dexdump -a -d on each to produce classes9-dump.txt, classes16-dump.txt, classes17-dump.txt in this directory.
3. Run `python extract_model_contract.py`, then `python extract_enum_contract.py`, then `python write_contract_report.py`.
4. model-contract-data.json retains fields, optional flags, exact DEX instruction addresses for descriptor additions, and evidence file paths. enum-contract-data.json retains constructor argument values and exact disassembly address evidence.

Enum script performs straight-line constant/move tracking through static constructors; names and numeric values listed are direct constructor arguments, not guesses from ordinal position. It does not prove downstream HTTP encoding. Exact endpoint routing, query/body encoding, choice of enum for an actual video, entitlement behavior, populated URL availability, and end-to-end playback require separate call-site evidence and/or permitted tests. Model evidence alone does not establish a complete working API client.
'''
(p/'model-contract.md').write_text(s)
print(p/'model-contract.md')
