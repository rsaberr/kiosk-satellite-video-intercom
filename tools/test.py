#!/usr/bin/env python3
"""Run the plugin's tests without a device: the call protocol over loopback, and the manifest's shape."""
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile

root = Path(__file__).resolve().parents[1]
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name

with tempfile.TemporaryDirectory(prefix='vi-test-') as directory:
    sources = [root / 'src/videointercom/CallLink.java', *sorted((root / 'tests').rglob('*.java'))]
    subprocess.run([tool('javac'), '-d', directory, *map(str, sources)], check=True)
    subprocess.run([tool('java'), '-ea', '-cp', directory, 'videointercom.CallLinkTest'], check=True)

manifest = json.loads((root / 'kiosk-satellite-plugin.json').read_text())
assert manifest['schemaVersion'] == 1 and manifest['apiVersion'] == 1
assert re.fullmatch(r'[a-z][a-z0-9-]{0,63}', manifest['id'])
assert re.fullmatch(r'\d{4}\.\d+\.\d+', manifest['minAppVersion'])
assert (root / 'assets' / manifest['icon']).is_file()
assert len(manifest['settings']) <= 50 and len(manifest['commands']) <= 20
keys = [setting['key'] for setting in manifest['settings']]
assert len(keys) == len(set(keys)), 'duplicate setting keys'
print('manifest: ok')
