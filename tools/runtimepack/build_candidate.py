#!/usr/bin/env python3
# Build from the hash-pinned official Android archive; never execute the Android worker on the host.
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import struct
import subprocess
import tarfile

VERSION = '3.14.4'
ARCHIVE_SHA256 = '410fff96f47d818136f91f79c8f83202e1364aeaab2022d00fa70e53007ebde1'
NDK = '27.3.13750724'
ROOT = Path(__file__).resolve().parents[2]

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--inspect-only', action='store_true')
    args = parser.parse_args()
    if sha(args.archive) != ARCHIVE_SHA256:
        raise SystemExit('Official archive SHA-256 mismatch')
    output = args.output.resolve()
    if output.exists():
        raise SystemExit('Output must not exist; preserve previous evidence')
    output.mkdir(parents=True)
    unpacked = output / 'upstream'
    unpacked.mkdir()
    with tarfile.open(args.archive) as archive:
        members = archive.getmembers()
        if len(members) > 10000 or sum(m.size for m in members) > 256 * 1024 * 1024:
            raise SystemExit('Archive bounds exceeded')
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or '..' in name.parts or not (member.isfile() or member.isdir() or member.issym()):
                raise SystemExit('Unsupported archive path/type')
            if member.issym():
                continue
            archive.extract(member, unpacked, filter='data')
    prefix = unpacked / 'prefix'
    print('OFFICIAL_ARCHIVE_HASH=PASS', flush=True)
    if args.inspect_only:
        print('INSPECTION_ONLY=NO_WORKER_BUILT')
        return
    sdk = Path(os.environ['ANDROID_HOME'])
    toolchain = sdk / 'ndk' / NDK / 'toolchains/llvm/prebuilt/linux-x86_64/bin'
    compiler = toolchain / 'aarch64-linux-android26-clang'
    native = output / 'jniLibs/arm64-v8a'
    native.mkdir(parents=True)
    for name in ['libpython3.14.so', 'libcrypto_python.so', 'libssl_python.so', 'libsqlite3_python.so']:
        shutil.copy2(prefix / 'lib' / name, native / name)
    executable = native / 'libetroute_python_worker.so'
    command = [str(compiler), '-std=c11', '-O2', '-fPIE', '-pie', '-Wall', '-Wextra', '-Werror',
               '-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1', '-Wl,-rpath,$ORIGIN',
               '-I' + str(prefix / 'include/python3.14'), str(ROOT / 'runtimepack/python-worker.c'),
               '-L' + str(native), '-lpython3.14', '-llog', '-ldl', '-lm', '-o', str(executable)]
    subprocess.run(command, check=True)
    payload = output / 'assets/runtimepack/payload/lib/python3.14'
    shutil.copytree(prefix / 'lib/python3.14', payload)
    for cache in payload.rglob('__pycache__'):
        shutil.rmtree(cache)
    assets = output / 'assets/runtimepack'
    inventory = dict(schemaVersion=1, id='cpython-3.14.4-arm64-acceptance', version='official-3.14.4-probe-v2',
                     pythonVersion=VERSION, abi='arm64-v8a', minApi=26, maxApi=35,
                     executable=executable.name, upstreamSha256=ARCHIVE_SHA256,
                     ndk=NDK, etrouteCommit=os.environ.get('GITHUB_SHA'), workerSha256=sha(ROOT / 'runtimepack/python-worker.c'),
                     nativeSha256={p.name: sha(p) for p in sorted(native.iterdir())},
                     payloadSha256={p.relative_to(assets / 'payload').as_posix(): sha(p)
                                    for p in sorted(payload.rglob('*')) if p.is_file()})
    if len(inventory['payloadSha256']) + len(inventory['nativeSha256']) > 4096:
        raise SystemExit('Preflight inventory entry ceiling exceeded')
    # Static architecture/PIE checks do not prove loader compatibility or successful execution.
    for path in native.iterdir():
        header = path.read_bytes()[:64]
        if header[:6] != b'\x7fELF\x02\x01' or struct.unpack_from('<H', header, 18)[0] != 183:
            raise SystemExit('Unexpected ELF architecture: ' + path.name)
    details = subprocess.check_output([str(toolchain / 'llvm-readelf'), '-h', '-l', '-d', str(executable)], text=True)
    if '/system/bin/linker64' not in details or 'libpython3.14.so' not in details:
        raise SystemExit('Missing Android interpreter or CPython dependency')
    (output / 'worker-readelf.txt').write_text(details)
    (output / 'compiler-version.txt').write_text(subprocess.check_output([str(compiler), '--version'], text=True))
    (output / 'build-command.json').write_text(json.dumps(command, indent=2) + '\n')
    (assets / 'inventory.json').write_text(json.dumps(inventory, sort_keys=True, indent=2) + '\n')
    # Runtime redistribution notices are carried inside the immutable upstream archive as well.
    shutil.copy2(payload / 'LICENSE.txt', output / 'PYTHON-LICENSE.txt')
    shutil.copy2(args.archive, output / args.archive.name)
    shutil.rmtree(unpacked)
    print('RUNTIMEPACK_CANDIDATE_BUILT=PASS')
    print('DEVICE_EXECUTION=UNVERIFIED')

if __name__ == '__main__':
    main()
