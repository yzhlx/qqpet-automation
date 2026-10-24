import re
import sys
from pathlib import Path

profile = Path.home() / '.dsh/profiles/desktop/cordis.patch.yml'
text = profile.read_text(encoding='utf-8-sig')
entries = re.findall(r'(?ms)^- id: dsh-android\s*\n(?:(?!^- ).*\n?)*', text)
if len(entries) != 1:
    print(f'FAIL Android override count={len(entries)}; inspect configuration')
    sys.exit(2)
disabled = bool(re.search(r'^  disabled:\s*true\s*$', entries[0], re.M))
print('FAIL Android plugin explicitly disabled in active desktop profile' if disabled else 'PASS Android plugin is not explicitly disabled in active desktop profile')
print('NOTE Configuration check only; runtime registration and device invocation are separate gates.')
sys.exit(1 if disabled else 0)
