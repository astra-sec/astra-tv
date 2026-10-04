#!/usr/bin/env python3
"""Export committed application and submodule sources without local build files."""
from datetime import datetime, timezone
from pathlib import Path, PurePosixPath
import hashlib
import io
import os
import posixpath
import re
import stat
import subprocess
import tarfile
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parent.parent
FFMPEG_PATH = PurePosixPath('third_party/ffmpeg')
FFMPEG_COMMIT = 'c41ff724ede7da657762d61097e26fac296c53bf'
FFMPEG_VERSION = b'6.0.1\n'


def git(repository, *arguments):
    return subprocess.check_output(['git', *arguments], cwd=repository)


def safe_path(name):
    filename = PurePosixPath(name)
    if filename.is_absolute() or not filename.parts or any(
        part in {'.', '..', '.git'} for part in filename.parts
    ):
        raise ValueError(f'Unsafe source archive path: {name!r}')
    return filename


def collect_repositories(repository, commit, prefix=PurePosixPath('.')):
    """Validate each checkout and recursively follow the committed gitlinks."""
    top = Path(git(repository, 'rev-parse', '--show-toplevel').decode().strip())
    if top.resolve() != repository.resolve():
        raise ValueError(
            f'Submodule {prefix} is not initialized; run '
            'git submodule update --init --recursive'
        )
    current = git(repository, 'rev-parse', '--verify', 'HEAD').decode().strip()
    if current != commit:
        raise ValueError(f'{prefix} must be checked out at committed revision {commit}')
    result = subprocess.run(
        ['git', 'diff', '--quiet', '--ignore-submodules=all', commit, '--', '.'],
        cwd=repository,
    )
    if result.returncode == 1:
        raise ValueError(f'Commit the matching source revision before packaging: {prefix}')
    result.check_returncode()

    if prefix == FFMPEG_PATH:
        if commit != FFMPEG_COMMIT or git(repository, 'show', f'{commit}:RELEASE') != FFMPEG_VERSION:
            raise ValueError('FFmpeg pin/version changed; update the corresponding-source metadata')

    repositories = [(repository, commit, prefix)]
    for entry in git(repository, 'ls-tree', '-r', '-z', commit).split(b'\0'):
        if not entry:
            continue
        metadata, filename = entry.split(b'\t', 1)
        mode, kind, object_id = metadata.split(b' ')
        if mode == b'160000' and kind == b'commit':
            child = safe_path(filename.decode('utf-8'))
            child_repository = repository.joinpath(*child.parts)
            if not child_repository.is_dir():
                raise ValueError(
                    f'Submodule {prefix / child} is not initialized; run '
                    'git submodule update --init --recursive'
                )
            repositories.extend(collect_repositories(
                child_repository, object_id.decode('ascii'), prefix / child,
            ))
    return repositories


def zip_entry(filename, mode, modified):
    timestamp = datetime.fromtimestamp(modified, timezone.utc)
    # ZIP timestamps have a smaller range than Git commit timestamps.
    year = min(2107, max(1980, timestamp.year))
    entry = zipfile.ZipInfo(str(filename), (year, *timestamp.timetuple()[1:6]))
    entry.create_system = 3
    entry.external_attr = mode << 16
    return entry


def export_repository(destination, repository, commit, prefix):
    archive = git(repository, 'archive', '--format=tar', commit)
    version_seen = False
    with tarfile.open(fileobj=io.BytesIO(archive), mode='r:') as source:
        for member in source:
            filename = safe_path(member.name)
            if member.isdir():
                continue
            output_path = PurePosixPath('astra-tv') / prefix / filename
            if member.isfile():
                data = source.extractfile(member).read()
                mode = stat.S_IFREG | member.mode
            elif member.issym():
                target = posixpath.normpath(str(filename.parent / member.linkname))
                if target.startswith('/') or target == '..' or target.startswith('../'):
                    raise ValueError(f'Unsafe source archive symlink: {member.name!r}')
                data = member.linkname.encode('utf-8')
                mode = stat.S_IFLNK | member.mode
            else:
                raise ValueError(f'Unsupported source archive entry: {member.name!r}')
            if prefix == FFMPEG_PATH and filename == PurePosixPath('VERSION'):
                if data != FFMPEG_VERSION:
                    raise ValueError('FFmpeg VERSION differs from its recorded release')
                version_seen = True
            destination.writestr(
                zip_entry(output_path, mode, member.mtime), data,
                compress_type=zipfile.ZIP_DEFLATED, compresslevel=6,
            )
        if prefix == FFMPEG_PATH and not version_seen:
            # Official release tarballs include VERSION; the matching Git tag does not.
            modified = int(git(repository, 'show', '-s', '--format=%ct', commit))
            destination.writestr(
                zip_entry(PurePosixPath('astra-tv') / prefix / 'VERSION', stat.S_IFREG | 0o644, modified),
                FFMPEG_VERSION, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6,
            )


def sha256(filename):
    digest = hashlib.sha256()
    with filename.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def main():
    gradle = (ROOT / 'app/build.gradle.kts').read_text()
    match = re.search(r'^\s*versionName\s*=\s*"([^"\r\n]+)"', gradle, re.MULTILINE)
    if match is None:
        raise ValueError('No versionName found in app/build.gradle.kts')
    version = match.group(1)
    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._-]*', version):
        raise ValueError(f'Invalid versionName for artifact filename: {version!r}')
    artifact_dir = ROOT / 'artifacts'
    apk = artifact_dir / f'astra-tv-{version}.apk'
    if not apk.is_file():
        raise ValueError(f'Copy the matching built APK to {apk} before packaging sources')

    commit = git(ROOT, 'rev-parse', '--verify', 'HEAD').decode().strip()
    repositories = collect_repositories(ROOT, commit)
    artifact_dir.mkdir(exist_ok=True)
    output = artifact_dir / f'astra-tv-{version}-source.zip'
    temporary_zip = None
    temporary_checksums = None
    try:
        with tempfile.NamedTemporaryFile(dir=artifact_dir, prefix=f'.{output.name}.', delete=False) as temporary:
            temporary_zip = Path(temporary.name)
        with zipfile.ZipFile(temporary_zip, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as destination:
            for repository, revision, prefix in repositories:
                export_repository(destination, repository, revision, prefix)
        checksums = f'{sha256(apk)}  {apk.name}\n{sha256(temporary_zip)}  {output.name}\n'
        with tempfile.NamedTemporaryFile(dir=artifact_dir, prefix='.SHA256SUMS.', delete=False, mode='w') as temporary:
            temporary_checksums = Path(temporary.name)
            temporary.write(checksums)
        os.replace(temporary_zip, output)
        os.replace(temporary_checksums, artifact_dir / 'SHA256SUMS.txt')
    finally:
        for temporary in [temporary_zip, temporary_checksums]:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    print(output)
    print('APK:', apk.stat().st_size, 'bytes')
    print('Corresponding source:', output.stat().st_size, 'bytes')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
