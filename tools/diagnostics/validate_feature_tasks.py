import json
import re
from collections import Counter
from pathlib import Path

root = Path(__file__).resolve().parents[2]
active = json.loads((root / '.specify/feature.json').read_text(encoding='utf-8-sig'))
feature = root / active['feature_directory']
text = (feature / 'tasks.md').read_text(encoding='utf-8-sig')
pattern = re.compile(r'- \[ \] (T\d{3})(?: (\[P\]))?(?: (\[US[1-5]\]))? (.+)')
rows = []
phase_story = None
for number, line in enumerate(text.splitlines(), 1):
    if line.startswith('## '):
        match = re.match(r'## Phase \d+: (US[1-5])', line)
        phase_story = match.group(1) if match else None
    if not line.startswith('- ['):
        continue
    match = pattern.fullmatch(line)
    assert match, f'Invalid task format at line {number}'
    task, parallel, story_tag, description = match.groups()
    story = story_tag.strip('[]') if story_tag else None
    assert story == phase_story, f'Wrong/missing story label for {task}: {story} != {phase_story}'
    paths = re.findall(r'`([^`]+)`', description)
    assert any('/' in path and re.search(r'\.(kt|md|py|ps1)$', path) for path in paths), f'Missing exact file path for {task}'
    rows.append((task, bool(parallel), story))
expected = [f'T{i:03d}' for i in range(1, 65)]
assert [row[0] for row in rows] == expected, 'Task IDs are not unique and sequential T001-T064'
counts = Counter(row[2] or 'shared' for row in rows)
assert counts == {'shared': 19, 'US1': 14, 'US2': 10, 'US3': 8, 'US4': 6, 'US5': 7}, counts
parallel = [row[0] for row in rows if row[1]]
assert len(parallel) == 10, parallel
for prefix, total in [('FR', 18), ('SC', 7)]:
    for index in range(1, total + 1):
        assert re.search(rf'^\| {prefix}-{index:03d} \|', text, re.M), f'Missing {prefix}-{index:03d} coverage row'
for label, target in re.findall(r'\[([^\]]+)\]\(([^)]+)\)', text):
    if '://' not in target and not target.startswith('#'):
        assert (feature / target.split('#')[0]).exists(), f'Broken link: {target}'
print(json.dumps({'result': 'PASS', 'tasks': len(rows), 'counts': counts, 'parallel': parallel, 'FR': 18, 'SC': 7, 'checks': ['checkbox format', 'sequential unique IDs', 'phase story labels', 'exact paths', 'counts', 'requirement coverage rows', 'local links']}, ensure_ascii=False))
