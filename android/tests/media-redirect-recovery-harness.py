#!/usr/bin/env python3
"""Keep actual NetworkGuard validators/types; replace only five-arg transfer I/O.
No test performs DNS/TLS/HTTP. Reflection recreates the old redirect expression on
baseline source (not a fake typed implementation), so failures are behavioral.
"""
from pathlib import Path
import sys
source=Path(sys.argv[1]).read_text()
needle='    static void download(String value, File destination, long maxBytes, String userAgent, String referer) throws Exception {'
start=source.index(needle); body=start+len(needle)
# Lexical matching preserves every other production method byte-for-byte.
i=body; depth=1; state='code'
while depth:
 c=source[i]; nxt=source[i:i+2]
 if state=='code':
  if nxt=='//':state='line';i+=1
  elif nxt=='/*':state='block';i+=1
  elif c=='"':state='string'
  elif c=="'":state='char'
  elif c=='{':depth+=1
  elif c=='}':depth-=1
 elif state=='line' and c=='\n':state='code'
 elif state=='block' and nxt=='*/':state='code';i+=1
 elif state in ('string','char'):
  if c=='\\':i+=1
  elif c==('"' if state=='string' else "'"):state='code'
 i+=1
original_download=source[body:i-1]
redirect_marker='if (Arrays.asList(301, 302, 303, 307, 308).contains(status)) {'
redirect_start=original_download.index(redirect_marker)+len(redirect_marker)
redirect_end=original_download.index('\n                        }',redirect_start)
redirect_branch=original_download[redirect_start:redirect_end]
assert redirect_branch.count('continue;')==1
redirect_branch=redirect_branch.replace('continue;','return uri;')
source=source[:body]+'''\n        RedirectTransferFixture.download(value,destination,maxBytes,userAgent,referer);\n    }'''+source[i:]
# Execute the original 3xx branch offline with the final loop continue mapped
# to returning the next URI. This covers missing headers and redirect limit,
# without claiming any real socket or HTTP exchange occurred.
end=source.rfind('}')
source=source[:end]+'\n    static URI fixtureRedirectResponse(int redirects, URI uri, Map<String,String> headers) throws Exception {\n'+redirect_branch+'\n    }\n'+source[end:]
Path(sys.argv[2]).write_text(source)
print('HARNESS actualNetworkGuardValidation=true actualMediaRedirectIfPresent=true replacedMethods=downloadFiveArg copied3xxBranch=true networkRequests=0')
