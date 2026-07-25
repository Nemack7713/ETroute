#!/usr/bin/env python3
"""ETroute: a stdlib-only, rootless PRoot environment manager starter.

This starter keeps rootfs installation separate from guest package execution,
models PRoot execution modes explicitly, validates names and binds, and records
provenance. It never invokes host privilege-escalation tools.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
import uuid
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from enum import Enum
from pathlib import Path, PurePosixPath
from typing import Sequence

LOG = logging.getLogger("etroute")
NAME_RE = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
SHA256_RE = re.compile(r"^[0-9a-f]{64}$")

S_MODE_RELATIVE_PATHS: tuple[str, ...] = (
    "/etc/host.conf",
    "/etc/hosts",
    "/etc/nsswitch.conf",
    "/etc/resolv.conf",
    "/dev",
    "/sys",
    "/proc",
    "/tmp",
    "/run/shm",
    "$HOME",
)

PACKAGE_MODE_FORBIDDEN_GUESTS = frozenset({
    "/etc/passwd", "/etc/group", "/etc/shadow", "/etc/gshadow",
})


class ExitCode:
    OK = 0
    CLI = 2
    CHECKSUM = 3
    DEPENDENCY = 4
    FILESYSTEM = 5
    CONFLICT = 6
    VALIDATION = 7
    RUNTIME = 8


class ExecutionMode(str, Enum):
    PACKAGE = "package"
    LOGIN_ROOT = "login-root"
    LOGIN_USER = "login-user"


class ETRouteError(RuntimeError):
    def __init__(self, message: str, code: int = ExitCode.RUNTIME) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class Layout:
    runtime_dir: Path

    @property
    def containers_dir(self) -> Path:
        return self.runtime_dir / "containers"

    @property
    def cache_dir(self) -> Path:
        return self.runtime_dir / "cache"

    @property
    def sessions_dir(self) -> Path:
        return self.runtime_dir / "sessions"

    def ensure(self) -> None:
        for directory in (self.containers_dir, self.cache_dir, self.sessions_dir):
            directory.mkdir(parents=True, exist_ok=True)

    def container_dir(self, name: str) -> Path:
        validate_name(name)
        return self.containers_dir / name

    def rootfs_dir(self, name: str) -> Path:
        return self.container_dir(name) / "rootfs"

    def manifest_path(self, name: str) -> Path:
        return self.container_dir(name) / "manifest.json"


@dataclass(frozen=True)
class Provenance:
    schema_version: int
    image: str
    version: str
    arch: str
    source: str
    sha256: str
    captured_at: str = field(default_factory=lambda: datetime.now(timezone.utc).isoformat())

    def write_atomic(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(f".{path.name}.tmp-{os.getpid()}")
        tmp.write_text(json.dumps(asdict(self), indent=2, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(tmp, path)

    @classmethod
    def read(cls, path: Path) -> "Provenance":
        try:
            return cls(**json.loads(path.read_text(encoding="utf-8")))
        except (OSError, TypeError, ValueError, json.JSONDecodeError) as exc:
            raise ETRouteError(f"invalid manifest {path}: {exc}", ExitCode.VALIDATION) from exc


@dataclass(frozen=True)
class BindMount:
    host: Path
    guest: str
    no_dereference: bool = False

    def validate(self) -> "BindMount":
        host = self.host.expanduser().resolve()
        if not host.exists():
            raise ETRouteError(f"bind source does not exist: {host}", ExitCode.VALIDATION)
        guest = PurePosixPath(self.guest)
        if not guest.is_absolute() or ".." in guest.parts:
            raise ETRouteError(f"guest bind destination must be absolute and traversal-free: {self.guest}", ExitCode.VALIDATION)
        return BindMount(host=host, guest=str(guest), no_dereference=self.no_dereference)

    def serialize(self) -> str:
        validated = self.validate()
        suffix = "!" if validated.no_dereference else ""
        return f"{validated.host}:{validated.guest}{suffix}"


def default_runtime_dir() -> Path:
    if override := os.environ.get("ETROUTE_HOME"):
        return Path(override).expanduser()
    prefix = os.environ.get("PREFIX", "")
    if "com.termux" in prefix:
        return Path(prefix) / "var" / "lib" / "etroute"
    xdg = os.environ.get("XDG_DATA_HOME")
    return Path(xdg).expanduser() / "etroute" if xdg else Path.home() / ".local" / "share" / "etroute"


def validate_name(name: str) -> None:
    if not NAME_RE.fullmatch(name):
        raise ETRouteError(
            "environment name must match [A-Za-z0-9][A-Za-z0-9._-]{0,63}",
            ExitCode.CLI,
        )


def normalize_sha256(value: str) -> str:
    normalized = value.strip().lower()
    if not SHA256_RE.fullmatch(normalized):
        raise ETRouteError("SHA-256 must be exactly 64 hexadecimal characters", ExitCode.CLI)
    return normalized


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def verify_checksum(path: Path, expected: str) -> str:
    expected = normalize_sha256(expected)
    actual = sha256_of(path)
    if actual != expected:
        raise ETRouteError(
            f"checksum mismatch for {path.name}: expected {expected}, got {actual}",
            ExitCode.CHECKSUM,
        )
    return actual


def safe_extract_tar(tarball: Path, destination: Path) -> None:
    destination = destination.resolve()
    try:
        with tarfile.open(tarball, "r:*") as archive:
            for member in archive.getmembers():
                member_path = destination / member.name
                try:
                    member_path.resolve().relative_to(destination)
                except ValueError as exc:
                    raise ETRouteError(
                        f"unsafe archive member escapes destination: {member.name}",
                        ExitCode.VALIDATION,
                    ) from exc
                if member.isdev():
                    raise ETRouteError(
                        f"device-node archive member is not accepted by this starter: {member.name}",
                        ExitCode.VALIDATION,
                    )
            archive.extractall(destination)
    except (tarfile.TarError, OSError) as exc:
        raise ETRouteError(f"could not extract {tarball}: {exc}", ExitCode.FILESYSTEM) from exc


def validate_rootfs(rootfs: Path) -> str:
    for shell in ("bin/sh", "usr/bin/sh", "bin/bash", "usr/bin/bash"):
        if (rootfs / shell).is_file():
            return "/" + shell
    raise ETRouteError(f"rootfs has no usable shell: {rootfs}", ExitCode.VALIDATION)


def install_rootfs(
    *, layout: Layout, name: str, tarball: Path, expected_sha256: str,
    image: str, version: str, arch: str, replace: bool = False,
) -> None:
    validate_name(name)
    tarball = tarball.expanduser().resolve()
    if not tarball.is_file():
        raise ETRouteError(f"tarball does not exist: {tarball}", ExitCode.CLI)
    digest = verify_checksum(tarball, expected_sha256)
    final_dir = layout.container_dir(name)

    if final_dir.exists():
        manifest_path = layout.manifest_path(name)
        if manifest_path.exists() and not replace:
            existing = Provenance.read(manifest_path)
            if existing.sha256 == digest and existing.image == image and existing.version == version and existing.arch == arch:
                LOG.info("%s already installed with matching provenance", name)
                return
        if not replace:
            raise ETRouteError(f"environment already exists with different or incomplete state: {name}", ExitCode.CONFLICT)

    staging_parent = layout.containers_dir
    with tempfile.TemporaryDirectory(prefix=f".{name}.staging-", dir=staging_parent) as temp:
        staging = Path(temp)
        rootfs = staging / "rootfs"
        rootfs.mkdir()
        safe_extract_tar(tarball, rootfs)
        validate_rootfs(rootfs)
        Provenance(
            schema_version=1,
            image=image,
            version=version,
            arch=arch,
            source=str(tarball),
            sha256=digest,
        ).write_atomic(staging / "manifest.json")

        backup = None
        if final_dir.exists():
            backup = final_dir.with_name(f".{name}.replaced-{os.getpid()}")
            os.replace(final_dir, backup)
        try:
            os.replace(staging, final_dir)
        except OSError:
            if backup and backup.exists() and not final_dir.exists():
                os.replace(backup, final_dir)
            raise
        else:
            if backup and backup.exists():
                shutil.rmtree(backup)


def parse_bind(spec: str) -> BindMount:
    if ":" not in spec:
        raise ETRouteError("bind must use HOST:GUEST or HOST:GUEST!", ExitCode.CLI)
    host, guest = spec.split(":", 1)
    no_deref = guest.endswith("!")
    if no_deref:
        guest = guest[:-1]
    return BindMount(Path(host), guest, no_deref).validate()


def package_policy_binds() -> list[BindMount]:
    home = os.environ.get("HOME")
    if not home:
        raise ETRouteError("HOME is required for package execution policy", ExitCode.VALIDATION)
    result: list[BindMount] = []
    for raw in S_MODE_RELATIVE_PATHS:
        host_text = home if raw == "$HOME" else raw
        host = Path(host_text)
        if host.exists():
            result.append(BindMount(host, host_text).validate())
    return result


def validate_package_extra_binds(binds: Sequence[BindMount]) -> None:
    for bind in binds:
        guest = str(PurePosixPath(bind.guest))
        if guest in PACKAGE_MODE_FORBIDDEN_GUESTS:
            raise ETRouteError(
                f"package mode forbids binding host identity file onto {guest}",
                ExitCode.VALIDATION,
            )


def build_proot_argv(
    rootfs: Path,
    *,
    mode: ExecutionMode,
    binds: Sequence[BindMount] = (),
    command: Sequence[str] = (),
    kernel_release: str | None = None,
    proot_binary: str = "proot",
    kill_on_exit: bool = False,
) -> list[str]:
    rootfs = rootfs.expanduser().resolve()
    if not rootfs.is_dir():
        raise ETRouteError(f"rootfs does not exist: {rootfs}", ExitCode.VALIDATION)

    argv = [proot_binary, "--rootfs", str(rootfs)]
    if kernel_release:
        argv.append(f"--kernel-release={kernel_release}")
    if kill_on_exit:
        argv.append("--kill-on-exit")

    effective_binds: list[BindMount]
    if mode is ExecutionMode.PACKAGE:
        argv.append("--root-id")
        validate_package_extra_binds(binds)
        effective_binds = package_policy_binds() + list(binds)
    elif mode is ExecutionMode.LOGIN_ROOT:
        argv.append("--root-id")
        effective_binds = list(binds)
    else:
        effective_binds = list(binds)

    seen_guests: set[str] = set()
    for bind in effective_binds:
        validated = bind.validate()
        if validated.guest in seen_guests:
            raise ETRouteError(f"duplicate guest bind target: {validated.guest}", ExitCode.VALIDATION)
        seen_guests.add(validated.guest)
        argv += ["--bind", validated.serialize()]

    argv += ["--", *(command or ["/bin/sh"])]
    return argv


def proot_supports(binary: str, flag: str) -> bool:
    try:
        result = subprocess.run(
            [binary, "--help"], stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            text=True, encoding="utf-8", errors="replace", timeout=10, check=False,
        )
    except (OSError, subprocess.TimeoutExpired):
        return False
    return flag in result.stdout


def run_proot(argv: Sequence[str], env: dict[str, str] | None = None) -> int:
    LOG.debug("exec: %s", " ".join(argv))
    try:
        return subprocess.run(list(argv), check=False, env=env).returncode
    except FileNotFoundError as exc:
        raise ETRouteError("proot executable not found", ExitCode.DEPENDENCY) from exc


def cmd_install(args: argparse.Namespace, layout: Layout) -> int:
    install_rootfs(
        layout=layout,
        name=args.name,
        tarball=Path(args.tarball),
        expected_sha256=args.sha256,
        image=args.image or args.name,
        version=args.version or "unknown",
        arch=args.arch or "unknown",
        replace=args.replace,
    )
    LOG.info("installed %s", args.name)
    return ExitCode.OK


def cmd_list(args: argparse.Namespace, layout: Layout) -> int:
    if not layout.containers_dir.exists():
        print("(no environments)")
        return 0
    for entry in sorted(layout.containers_dir.iterdir()):
        if not entry.is_dir() or entry.name.startswith("."):
            continue
        manifest = entry / "manifest.json"
        try:
            prov = Provenance.read(manifest)
            shell = validate_rootfs(entry / "rootfs")
            print(f"{entry.name}\t{prov.image}:{prov.version}\t{prov.arch}\tready\t{shell}")
        except ETRouteError as exc:
            print(f"{entry.name}\tinvalid\t{exc}")
    return 0


def cmd_run(args: argparse.Namespace, layout: Layout) -> int:
    rootfs = layout.rootfs_dir(args.name)
    validate_rootfs(rootfs)
    binds = [parse_bind(spec) for spec in args.bind]
    if args.mode == ExecutionMode.PACKAGE.value and not args.command:
        raise ETRouteError("package mode requires an explicit command", ExitCode.CLI)
    mode = ExecutionMode(args.mode)
    kill = args.kill_on_exit and proot_supports(args.proot, "--kill-on-exit")
    if args.kill_on_exit and not kill:
        LOG.warning("selected PRoot does not advertise --kill-on-exit; continuing without it")
    argv = build_proot_argv(
        rootfs,
        mode=mode,
        binds=binds,
        command=args.command,
        kernel_release=args.kernel_release,
        proot_binary=args.proot,
        kill_on_exit=kill,
    )
    return run_proot(argv)


def diagnostic_script_path() -> Path:
    return Path(__file__).resolve().parent / "tools" / "first_activation_diagnostic.py"


def cmd_diagnose(args: argparse.Namespace, layout: Layout) -> int:
    rootfs = layout.rootfs_dir(args.name)
    validate_rootfs(rootfs)
    script = Path(args.script).expanduser().resolve() if args.script else diagnostic_script_path()
    if not script.is_file():
        raise ETRouteError(f"diagnostic script not found: {script}", ExitCode.DEPENDENCY)

    binds = [
        BindMount(Path(path), path)
        for path in ("/dev", "/proc", "/sys", "/tmp")
        if Path(path).exists()
    ]
    binds.append(BindMount(script, "/tmp/etroute-first-activation.py", True))

    kill = args.kill_on_exit and proot_supports(args.proot, "--kill-on-exit")
    if args.kill_on_exit and not kill:
        LOG.warning("selected PRoot does not advertise --kill-on-exit; continuing without it")

    command = ([args.python, "python3"] if args.python == "/usr/bin/env" else [args.python]) + ["/tmp/etroute-first-activation.py"]
    if args.strict:
        command.append("--strict")

    argv = build_proot_argv(
        rootfs,
        mode=ExecutionMode.LOGIN_ROOT,
        binds=binds,
        command=command,
        kernel_release=args.kernel_release,
        proot_binary=args.proot,
        kill_on_exit=kill,
    )
    env = os.environ.copy()
    env["HOME"] = "/root"
    env["ETROUTE_ACTIVATION_MARKER"] = "/root/.etroute_activation_marker"
    if args.kernel_release:
        env["ETROUTE_EXPECTED_KERNEL_RELEASE"] = args.kernel_release
    if args.caller:
        env["ETROUTE_CALLER"] = args.caller
    env["ETROUTE_ENVIRONMENT_NAME"] = args.name
    env["ETROUTE_SESSION_ID"] = args.session_id or uuid.uuid4().hex
    return run_proot(argv, env=env)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(prog="etroute", description=__doc__.splitlines()[0])
    parser.add_argument("-v", "--verbose", action="store_true")
    sub = parser.add_subparsers(dest="cmd", required=True)

    install = sub.add_parser("install", help="transactionally install a local rootfs tarball")
    install.add_argument("name")
    install.add_argument("--tarball", required=True)
    install.add_argument("--sha256", required=True)
    install.add_argument("--image")
    install.add_argument("--version")
    install.add_argument("--arch")
    install.add_argument("--replace", action="store_true")
    install.set_defaults(func=cmd_install)

    listing = sub.add_parser("list", help="list and validate installed environments")
    listing.set_defaults(func=cmd_list)

    run = sub.add_parser("run", aliases=["login"], help="run a command or shell in an environment")
    run.add_argument("name")
    run.add_argument(
        "--mode", choices=[mode.value for mode in ExecutionMode],
        default=ExecutionMode.LOGIN_ROOT.value,
    )
    run.add_argument("--bind", action="append", default=[], help="HOST:GUEST or HOST:GUEST!")
    run.add_argument("--kernel-release", default=None)
    run.add_argument("--proot", default="proot")
    run.add_argument("--kill-on-exit", action="store_true")
    run.add_argument("command", nargs=argparse.REMAINDER)
    run.set_defaults(func=cmd_run)

    diagnose = sub.add_parser("diagnose", help="run the offline first-activation diagnostic inside a guest")
    diagnose.add_argument("name")
    diagnose.add_argument("--script", help="override the bundled diagnostic script path")
    diagnose.add_argument("--python", default="/usr/bin/env", help="guest Python launcher; default uses /usr/bin/env")
    diagnose.add_argument("--kernel-release", default=None)
    diagnose.add_argument("--proot", default="proot")
    diagnose.add_argument("--kill-on-exit", action="store_true")
    diagnose.add_argument("--caller", choices=("etumax", "alyshia", "manual"), default="manual")
    diagnose.add_argument("--strict", action="store_true")
    diagnose.add_argument("--session-id", help="optional caller-provided correlation identifier")
    diagnose.set_defaults(func=cmd_diagnose)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    logging.basicConfig(
        level=logging.DEBUG if args.verbose else logging.INFO,
        format="%(levelname)s %(name)s: %(message)s",
    )
    layout = Layout(default_runtime_dir())
    try:
        layout.ensure()
        return args.func(args, layout)
    except ETRouteError as exc:
        LOG.error("%s", exc)
        return exc.code
    except KeyboardInterrupt:
        LOG.error("interrupted")
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
