"""Build and verify a portable release from an already-built mod JAR (stdlib only)."""
from pathlib import Path
import gzip
import hashlib
import io
import json
import re
import shutil
import tarfile
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "unity/Packages/dev.avatarcraft.exporter"
METADATA = json.loads((PACKAGE / "package.json").read_text(encoding="utf-8"))
VERSION = METADATA["version"]
if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?", VERSION):
    raise ValueError("Invalid package version")
NAME = f"AvatarCraft-{VERSION}"
OUTPUT = ROOT / "PublishPackage"
DEST = OUTPUT / NAME
ARCHIVE = OUTPUT / f"{NAME}.zip"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def verify_jar(data):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        assert jar.testzip() is None
        toml = jar.read("META-INF/neoforge.mods.toml").decode("utf-8")
        assert f'version="{VERSION}"' in toml
        assert 'license="All Rights Reserved"' in toml
        assert "dev/avatarcraft/client/AvatarFirstPerson.class" in jar.namelist()
        assert all(not n.startswith(("net/minecraft/", "net/neoforged/")) for n in jar.namelist())


def make_upm(destination):
    # Unity Add package from tarball expects a package/ root. Preserve every Unity GUID.
    with destination.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w", format=tarfile.PAX_FORMAT) as tar:
                for source in sorted(PACKAGE.rglob("*")):
                    if not source.is_file():
                        continue
                    relative = source.relative_to(PACKAGE).as_posix()
                    if source.suffix not in {".cs", ".json", ".asmdef", ".meta"}:
                        raise ValueError(f"Unexpected Unity package file: {relative}")
                    data = source.read_bytes()
                    info = tarfile.TarInfo(f"package/{relative}")
                    info.size, info.mode, info.mtime = len(data), 0o644, 0
                    tar.addfile(info, io.BytesIO(data))
                for name, source in (("README.md", ROOT / "release/README_ja.md"),
                                     ("LICENSE.txt", ROOT / "release/LICENSE.txt")):
                    data = source.read_bytes()
                    info = tarfile.TarInfo(f"package/{name}")
                    info.size, info.mode, info.mtime = len(data), 0o644, 0
                    tar.addfile(info, io.BytesIO(data))
    with tarfile.open(destination, "r:gz") as tar:
        members = tar.getmembers()
        assert len({m.name for m in members}) == len(members)
        assert all(m.isfile() and m.name.startswith("package/") and ".." not in Path(m.name).parts
                   for m in members)
        packed = {m.name: tar.extractfile(m).read() for m in members}
        for source in PACKAGE.rglob("*"):
            if source.is_file():
                assert packed[f"package/{source.relative_to(PACKAGE).as_posix()}"] == source.read_bytes()
        assert json.loads(packed["package/package.json"]) == METADATA
        library = json.loads(packed["package/Editor/Resources/AvatarCraft/motions.json"])
        assert library
        assert METADATA["dependencies"]["com.unity.nuget.newtonsoft-json"] == "3.2.1"


def main():
    if DEST.exists() or ARCHIVE.exists():
        raise FileExistsError(f"Release already exists: {NAME}; preserve it or use a new version")
    jar_source = ROOT / f"build/libs/avatarcraft-{VERSION}.jar"
    verify_jar(jar_source.read_bytes())
    OUTPUT.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".avatarcraft-", dir=OUTPUT) as temporary:
        stage = Path(temporary) / NAME
        (stage / "Minecraft/mods").mkdir(parents=True)
        (stage / "Unity").mkdir()
        (stage / "Docs").mkdir()
        shutil.copy2(jar_source, stage / "Minecraft/mods" / jar_source.name)
        for name in ("README_ja.md", "CHANGELOG_ja.md", "LICENSE.txt"):
            shutil.copy2(ROOT / "release" / name, stage / name)
        shutil.copy2(ROOT / "release/Guide_ja.md", stage / "Docs/Guide_ja.md")
        make_upm(stage / f"Unity/dev.avatarcraft.exporter-{VERSION}.tgz")
        files = [{"path": p.relative_to(stage).as_posix(), "size": p.stat().st_size,
                  "sha256": sha(p.read_bytes())} for p in sorted(stage.rglob("*")) if p.is_file()]
        manifest = {"name": "AvatarCraft", "version": VERSION,
                    "minecraft": "26.3", "neoforgeTested": "26.3.0.95", "java": 25,
                    "unityTested": "2022.3.22f1", "license": "All Rights Reserved",
                    "includesAvatarAssets": False, "files": files}
        (stage / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        sums = "".join(f"{sha(p.read_bytes())}  {p.relative_to(stage).as_posix()}\n"
                       for p in sorted(stage.rglob("*")) if p.is_file())
        (stage / "SHA256SUMS.txt").write_text(sums, encoding="utf-8")
        zip_path = Path(temporary) / ARCHIVE.name
        with zipfile.ZipFile(zip_path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for path in sorted(stage.rglob("*")):
                if path.is_file():
                    archive.write(path, f"{NAME}/{path.relative_to(stage).as_posix()}")
        with zipfile.ZipFile(zip_path) as archive:
            assert archive.testzip() is None
            assert len(archive.namelist()) == len(list(p for p in stage.rglob("*") if p.is_file()))
            for path in stage.rglob("*"):
                if path.is_file():
                    assert archive.read(f"{NAME}/{path.relative_to(stage).as_posix()}") == path.read_bytes()
            assert not any(n.lower().endswith((".avc", ".png", ".fbx", ".vrm")) for n in archive.namelist())
        for row in sums.splitlines():
            digest, name = row.split("  ", 1)
            assert sha((stage / name).read_bytes()) == digest
        shutil.move(str(stage), str(DEST))
        shutil.move(str(zip_path), str(ARCHIVE))
    (OUTPUT / f"{NAME}.zip.sha256").write_text(f"{sha(ARCHIVE.read_bytes())}  {ARCHIVE.name}\n", encoding="utf-8")
    print(f"Verified release: {ARCHIVE} ({ARCHIVE.stat().st_size:,} bytes)")
    print(f"Contents: {DEST}")


if __name__ == "__main__":
    main()
