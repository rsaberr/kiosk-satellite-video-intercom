#!/usr/bin/env python3
"""Build the plugin ZIP: compile the SDK interfaces and the plugin, convert to DEX with d8, package.

Writes dist/<id>-<version>.zip, its .sha256 and the release copy of kiosk-satellite-plugin.json. A release passes
--version from its tag; the manifest inside the ZIP and the release copy are then identical.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
MAX_BYTES = 4 * 1024 * 1024

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--version', help='Package version, normally from the release tag')
parser.add_argument('--android-platform', help='Installed platform to compile against, such as 35')
args = parser.parse_args()

source = (ROOT / 'kiosk-satellite-plugin.json').read_bytes()
manifest = json.loads(source)
if args.version is not None:
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:-[a-zA-Z0-9.-]+)?', args.version) or len(args.version) > 40:
        raise SystemExit('Version must be major.minor.patch with an optional prerelease suffix')
    manifest['version'] = args.version
    source = (json.dumps(manifest, indent=2, ensure_ascii=False) + '\n').encode('utf-8')

sdk = Path(os.environ.get('ANDROID_HOME', os.environ.get('ANDROID_SDK_ROOT', str(Path.home() / 'android-sdk'))))
if args.android_platform:
    platform = sdk / 'platforms' / f'android-{args.android_platform}' / 'android.jar'
else:
    platforms = [p for p in (sdk / 'platforms').glob('android-*/android.jar') if re.fullmatch(r'android-[0-9.]+', p.parent.name)]
    platform = max(platforms, key=lambda p: tuple(int(v) for v in p.parent.name[8:].split('.')), default=None)
if platform is None or not platform.exists():
    raise SystemExit('Set ANDROID_HOME to an Android SDK with a platform installed')
d8s = sorted((sdk / 'build-tools').glob('*/d8'), key=lambda p: tuple(int(v) for v in p.parent.name.split('.') if v.isdigit()))
if not d8s:
    raise SystemExit('Install Android build-tools for d8')
java_home = os.environ.get('JAVA_HOME')
def tool(name):
    return str(Path(java_home) / 'bin' / name) if java_home else name


def add(archive, name, data):
    info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o644 << 16
    archive.writestr(info, data)


assets = []
if (ROOT / 'assets').is_dir():
    for file in sorted((ROOT / 'assets').rglob('*')):
        if file.is_file():
            path = file.relative_to(ROOT / 'assets').as_posix()
            if not all(re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}', part) for part in path.split('/')):
                raise SystemExit(f'Invalid asset path: {path}')
            assets.append(('assets/' + path, file))

out = ROOT / 'dist'
out.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='vi-build-') as temp:
    temp = Path(temp)
    sdk_classes, classes, dex = temp / 'sdk', temp / 'classes', temp / 'dex'
    for folder in (sdk_classes, classes, dex):
        folder.mkdir()
    subprocess.run([tool('javac'), '-nowarn', '--release', '8', '-cp', str(platform), '-d', str(sdk_classes),
                    *map(str, sorted((ROOT / 'sdk/src').rglob('*.java')))], check=True)
    sdk_jar = temp / 'sdk.jar'
    subprocess.run([tool('jar'), 'cf', str(sdk_jar), '-C', str(sdk_classes), '.'], check=True)
    subprocess.run([tool('javac'), '-Xlint:-options', '--release', '8', '-cp', os.pathsep.join([str(sdk_jar), str(platform)]),
                    '-d', str(classes), *map(str, sorted((ROOT / 'src').rglob('*.java')))], check=True)
    subprocess.run([str(d8s[-1]), '--release', '--min-api', str(manifest['minAndroidSdk']), '--lib', str(platform),
                    '--classpath', str(sdk_jar), '--output', str(dex), *map(str, sorted(classes.rglob('*.class')))], check=True)
    jar = temp / 'plugin.jar'
    with zipfile.ZipFile(jar, 'w', zipfile.ZIP_DEFLATED) as archive:
        for file in sorted(dex.glob('*.dex')):
            add(archive, file.name, file.read_bytes())
    name = f"{manifest['id']}-{manifest['version']}.zip"
    package = out / name
    with zipfile.ZipFile(package, 'w', zipfile.ZIP_DEFLATED) as archive:
        add(archive, 'kiosk-satellite-plugin.json', source)
        add(archive, 'plugin.jar', jar.read_bytes())
        add(archive, 'LICENSE', (ROOT / 'LICENSE').read_bytes())
        for path, file in assets:
            add(archive, path, file.read_bytes())
    if package.stat().st_size > MAX_BYTES:
        package.unlink()
        raise SystemExit('Plugin ZIP must be at most 4 MB')
    digest = hashlib.sha256(package.read_bytes()).hexdigest()
    (out / (name + '.sha256')).write_text(f'{digest}  {name}\n')
    (out / 'kiosk-satellite-plugin.json').write_bytes(source)
    print(package, package.stat().st_size, 'bytes, sha256', digest)
