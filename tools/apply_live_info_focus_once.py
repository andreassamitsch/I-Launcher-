"""One-time focus-order change; removed by the applying workflow."""
from pathlib import Path
path = Path('app/src/main/java/com/andreassamitsch/ilauncher/ui/livetv/LiveTvPlayerScreen.kt')
text = path.read_text(encoding='utf-8')
replacements = [
    ('    val epgButtonFocusRequester = remember { FocusRequester() }',
     '    val infoButtonFocusRequester = remember { FocusRequester() }'),
    ('Modifier.focusRequester(overlayFocusRequester).focusProperties { down = epgButtonFocusRequester }',
     'Modifier.focusRequester(overlayFocusRequester).focusProperties { down = infoButtonFocusRequester }'),
    ('TouchButton(onClick = ::openProgramInfo) {',
     'TouchButton(onClick = ::openProgramInfo, modifier = Modifier.focusRequester(infoButtonFocusRequester)) {'),
    ('TouchButton(onClick = ::openEpg, modifier = Modifier.focusRequester(epgButtonFocusRequester)) {',
     'TouchButton(onClick = ::openEpg) {'),
]
for before, after in replacements:
    count = text.count(before)
    if count != 1:
        raise RuntimeError(f'Expected one occurrence of {before!r}; found {count}')
    text = text.replace(before, after, 1)
path.write_text(text, encoding='utf-8')
print('Live-TV D-pad focus now enters Info before EPG')
