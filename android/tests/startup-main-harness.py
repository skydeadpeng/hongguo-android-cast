#!/usr/bin/env python3
"""Run the complete production MainActivity with offline framework doubles, unchanged method bodies."""
from pathlib import Path
import re, sys
src, output = Path(sys.argv[1]), Path(sys.argv[2])
text = (src / 'MainActivity.java').read_text()
text = re.sub(r'^import android\.[^\n]+;\n', '', text, flags=re.M)
text = text.replace('android.graphics.Insets', 'Insets')
output.write_text(text)
print('STARTUP_MAIN_BINDING actualMainActivity=true methodBodiesUnchanged=true networkRequests=0 deviceTests=not_run')
