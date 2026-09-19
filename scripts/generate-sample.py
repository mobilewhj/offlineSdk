#!/usr/bin/env python3
"""Build only the checked-in synthetic demo; no downloads or external ZIP input."""
import argparse
import hashlib
import io
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]
FILES = ('index.html', 'style.css')
OUTPUT = ROOT / 'app/src/main/assets/sample.zip'
CONFIG = ROOT / 'app/src/main/java/com/offline/tool/sample/ui/welcome/WelcomeRepository.kt'
# Fixed metadata preserves the already published demo ZIP byte for byte.
TIMESTAMP = (2026, 9, 17, 11, 27, 50)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true',
                        help='Verify the ZIP and configured SHA-256 without writing files')
    args = parser.parse_args()
    source = ROOT / 'sample-web'
    entries = list(source.rglob('*'))
    if source.is_symlink() or any(path.is_symlink() for path in entries):
        parser.exit(1, 'sample-web must not contain symbolic links.\n')
    names = {path.relative_to(source).as_posix() for path in entries if path.is_file()}
    if names != set(FILES):
        parser.exit(1, 'sample-web must contain exactly the reviewed example files.\n')
    content = io.BytesIO()
    with zipfile.ZipFile(content, 'w') as archive:
        for name in FILES:
            info = zipfile.ZipInfo(name, TIMESTAMP)
            info.create_system = 3
            info.external_attr = 0o600 << 16
            archive.writestr(info, (source / name).read_bytes(),
                             compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
    generated = content.getvalue()
    digest = hashlib.sha256(generated).hexdigest()
    config = CONFIG.read_text(encoding='utf-8')
    pattern = re.compile(r"([\"'])([0-9a-f]{64})\1")
    matches = list(pattern.finditer(config))
    if len(matches) != 1:
        parser.exit(1, 'Expected exactly one demo SHA-256 in the configuration.\n')
    if args.check:
        if not OUTPUT.is_file() or OUTPUT.read_bytes() != generated:
            parser.exit(1, 'Demo ZIP differs from sample-web; run scripts/generate-sample.py.\n')
        if matches[0].group(2) != digest:
            parser.exit(1, 'Demo SHA-256 differs; run scripts/generate-sample.py.\n')
        print('Verified local sample ZIP and configured SHA-256: ' + digest)
        return
    updated = pattern.sub(lambda match: match.group(1) + digest + match.group(1), config)
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(generated)
    if updated != config:
        CONFIG.write_text(updated, encoding='utf-8')
    print('Generated local sample ZIP and configured SHA-256: ' + digest)


if __name__ == '__main__':
    main()
