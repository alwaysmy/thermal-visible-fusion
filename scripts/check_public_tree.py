#!/usr/bin/env python3
"""Reject accidental private/vendor capture or signing material in the git index.

This is a narrow publication guard, not a general-purpose secret scanner. It never
prints matched secret values. The sole bundled binary is the verified Gradle wrapper.
"""
from hashlib import sha256
from pathlib import PurePosixPath
import re
import subprocess
import sys

PRIVATE_PARTS = {'private_inputs', 'vendor_sdk', 'vendor-libs', '.venv', '.gradle', 'build'}
FORBIDDEN_SUFFIXES = {'.aar', '.apk', '.so', '.dex', '.jks', '.keystore', '.p12', '.pem',
                      '.key', '.raw', '.bin', '.npy', '.zip', '.rar', '.7z', '.pdf', '.log'}
WRAPPER = 'android/gradle/wrapper/gradle-wrapper.jar'
WRAPPER_SHA256 = '2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046'
SECRET_PATTERNS = [re.compile(rb'-----BEGIN [A-Z ]*PRIVATE KEY-----'),
                   re.compile(rb'\b(?:ghp|gho|ghu|ghs)_[A-Za-z0-9]{30,}\b'),
                   re.compile(rb'\bgithub_pat_[A-Za-z0-9_]{40,}\b')]


def rejection_reason(name, data):
    path = PurePosixPath(name)
    if PRIVATE_PARTS.intersection(path.parts) or path.suffix.lower() in FORBIDDEN_SUFFIXES:
        return 'private/vendor/capture/build path is not publishable'
    if path.name in {'local.properties', 'hosts.yml', '.env'} or path.name.startswith('.env.'):
        return 'local configuration is not publishable'
    if name == WRAPPER:
        return None if sha256(data).hexdigest() == WRAPPER_SHA256 else 'wrapper checksum differs from verified upstream'
    if path.suffix.lower() == '.jar' or b'\0' in data:
        return 'unreviewed binary file'
    try:
        data.decode('utf-8')
    except UnicodeDecodeError:
        return 'non-UTF-8 file needs explicit publication review'
    if any(pattern.search(data) for pattern in SECRET_PATTERNS):
        return 'credential-like content detected'
    return None


def main():
    names = subprocess.check_output(['git', 'ls-files', '-z']).decode().split('\0')
    failures = []
    checked = 0
    for name in filter(None, names):
        data = subprocess.check_output(['git', 'show', ':'+name])
        reason = rejection_reason(name, data)
        if reason: failures.append((name, reason))
        checked += 1
    for name, reason in failures:
        print(f'REJECT {name}: {reason}', file=sys.stderr)
    if failures: return 1
    print(f'Public-tree boundary check passed: {checked} indexed files')
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
