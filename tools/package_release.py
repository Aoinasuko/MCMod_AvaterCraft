"""Build and verify a portable release from an already-built mod JAR (stdlib only)."""
from pathlib import Path
import argparse
import gzip
import hashlib
import io
import json
import re
import shutil
import tarfile
import tempfile
import zipfile

from validate_release_docs import validate as validate_docs

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = ROOT / "unity/Packages/dev.avatarcraft.exporter"
METADATA = json.loads((PACKAGE / "package.json").read_text(encoding="utf-8"))
VERSION = METADATA["version"]
if not re.fullmatch(r"\d+\.\d+\.\d+(?:[-+][A-Za-z0-9.-]+)?", VERSION):
    raise ValueError("Invalid package version")
MINECRAFT_VERSION = "26.3"
MOD_VERSION = f"{MINECRAFT_VERSION}-{VERSION}"
if not re.search(rf"^version = '{re.escape(MOD_VERSION)}'$",
                 (ROOT / "build.gradle").read_text(encoding="utf-8"), re.MULTILINE):
    raise ValueError("Gradle mod version must match Minecraft version + exporter version")
PARSER = argparse.ArgumentParser(description=__doc__)
PARSER.add_argument("--revision", default="", help="Optional distribution suffix; preserve previous archives")
ARGS = PARSER.parse_args()
if ARGS.revision and not re.fullmatch(r"[a-z0-9][a-z0-9-]{0,31}", ARGS.revision):
    raise ValueError("Invalid distribution revision")
NAME = f"BEP-3DAvater-{MOD_VERSION}" + (f"-{ARGS.revision}" if ARGS.revision else "")
OUTPUT = ROOT / "PublishPackage"
DEST = OUTPUT / NAME
ARCHIVE = OUTPUT / f"{NAME}.zip"


def sha(data):
    return hashlib.sha256(data).hexdigest()


def license_text():
    return "AvatarCraft\nAll Rights Reserved.\n\nTemplate notice:\n" + (ROOT / "LICENSE").read_text(encoding="utf-8")


def guide_files():
    return sorted(p for p in (ROOT / "release").rglob("*") if p.is_file())


def make_unitypackage(destination):
    """Unity's GUID/asset/asset.meta/pathname format, retaining original source GUIDs."""
    assets = {}
    target_root = "Assets/BEP3DAvaterExporter"

    def add(pathname, content, metadata=None, folder=False):
        guid = sha(("bep-exporter:" + pathname).encode())[:32]
        if metadata is None:
            metadata = (f"fileFormatVersion: 2\nguid: {guid}\n" +
                        ("folderAsset: yes\nDefaultImporter:\n  externalObjects: {}\n" if folder
                         else "DefaultImporter:\n  externalObjects: {}\n")).encode()
        else:
            match = re.search(rb"^guid: ([a-f0-9]{32})\r?$", metadata, re.MULTILINE)
            assert match, f"Missing Unity GUID: {pathname}"
            guid = match.group(1).decode()
        assert guid not in assets, f"Duplicate Unity GUID: {pathname}"
        assets[guid] = {"pathname": pathname.encode(), "asset": content, "asset.meta": metadata}

    add(target_root, b"", folder=True)
    for source in sorted(PACKAGE.rglob("*")):
        relative = source.relative_to(PACKAGE).as_posix()
        if source.suffix == ".meta" or relative == "package.json":
            continue
        metadata = Path(str(source) + ".meta").read_bytes()
        add(f"{target_root}/{relative}", b"" if source.is_dir() else source.read_bytes(),
            metadata, source.is_dir())
    add(f"{target_root}/Documentation", b"", folder=True)
    add(f"{target_root}/Documentation/assets", b"", folder=True)
    for source in guide_files():
        add(f"{target_root}/Documentation/{source.relative_to(ROOT / 'release').as_posix()}", source.read_bytes())
    add(f"{target_root}/LICENSE.txt", license_text().encode())
    with destination.open("wb") as raw:
        with gzip.GzipFile(fileobj=raw, mode="wb", filename="", mtime=0) as compressed:
            with tarfile.open(fileobj=compressed, mode="w") as tar:
                for guid, entries in assets.items():
                    for name, data in entries.items():
                        info = tarfile.TarInfo(f"{guid}/{name}")
                        info.size, info.mode, info.mtime = len(data), 0o644, 0
                        tar.addfile(info, io.BytesIO(data))
    with tarfile.open(destination, "r:gz") as tar:
        assert len(tar.getmembers()) == len(assets) * 3
        for guid, entries in assets.items():
            for name, data in entries.items():
                assert tar.extractfile(f"{guid}/{name}").read() == data
        paths = {row["pathname"].decode() for row in assets.values()}
        assert f"{target_root}/Editor/AvatarCraftExportWindow.cs" in paths
        assert f"{target_root}/Editor/Resources/AvatarCraft/motions.json" in paths
        assert f"{target_root}/Documentation/index.html" in paths
        assert not any(p.endswith("package.json") for p in paths)


def verify_jar(data, role):
    with zipfile.ZipFile(io.BytesIO(data)) as jar:
        assert jar.testzip() is None
        toml = jar.read("META-INF/neoforge.mods.toml").decode("utf-8")
        assert f'version="{MOD_VERSION}"' in toml
        assert f'versionRange="[{MINECRAFT_VERSION}]"' in toml
        if role != "loader":
            assert f'versionRange="[{MOD_VERSION},' in toml
        assert 'license="All Rights Reserved"' in toml
        names = set(jar.namelist())
        mod_id = f"bep_3davater_{role}"
        declared_ids = re.findall(r'^modId="([^"]+)"', toml, re.MULTILINE)
        assert declared_ids[0] == mod_id
        entrypoint = {"loader": "dev/avatarcraft/bep_3davater_loader.class",
                      "player": "dev/avatarcraft/player/bep_3davater_player.class",
                      "companion": "dev/avatarcraft/npc/bep_3davater_companion.class"}[role]
        assert entrypoint in names
        dependencies = set(declared_ids[1:])
        assert dependencies == ({"minecraft", "neoforge"} if role == "loader"
                                else {"minecraft", "neoforge", "bep_3davater_loader"})
        required = {"loader": "dev/avatarcraft/api/AvatarActor.class",
                    "player": "dev/avatarcraft/player/client/AvatarFirstPerson.class",
                    "companion": "dev/avatarcraft/npc/ai/CodexBridge.class"}[role]
        assert required in jar.namelist()
        if role == "companion":
            assert 'modId="bep_3davater_companion"' in toml
            assert "dev/avatarcraft/api/AvatarActor.class" not in jar.namelist()
            for component in ("AvatarAnchorBlock", "AvatarAnchorBlockEntity", "NpcAnchors", "AnchorEditing"):
                assert f"dev/avatarcraft/npc/{component}.class" in jar.namelist()
            assert "dev/avatarcraft/npc/client/AnchorScreen.class" in jar.namelist()
            assert "dev/avatarcraft/npc/CompanionManagement.class" in names
            assert "dev/avatarcraft/npc/client/CompanionSettingsScreen.class" in names
            for component in ("AiModelScreen", "AiModelPickerScreen"):
                assert f"dev/avatarcraft/npc/client/{component}.class" in names
            for component in ("AiModelCatalog", "AiConnectionMethod"):
                assert f"dev/avatarcraft/npc/ai/{component}.class" in names
            assert b"dev/avatarcraft/npc/NpcNetwork" not in jar.read("dev/avatarcraft/npc/client/AiModelScreen.class")
            for component in ("ToolProgram", "ToolSchedule", "NpcToolQueue", "BlueprintSpec", "NpcConstruction", "NpcBlueprints"):
                assert f"dev/avatarcraft/npc/ai/{component}.class" in jar.namelist()
            assert "dev/avatarcraft/npc/client/BlueprintPreview.class" in jar.namelist()
            anchor_texture = "assets/bep_3davater_companion/textures/block/avatar_anchor.png"
            assert jar.read(anchor_texture) == (ROOT / "src/npc/resources" / anchor_texture).read_bytes()
            for recipe, center in (("avatar_anchor", "#minecraft:planks"), ("memory_journal", "minecraft:book")):
                data = json.loads(jar.read(f"data/bep_3davater_companion/recipe/{recipe}.json"))
                assert data["type"] == "minecraft:crafting_shaped"
                assert data["pattern"] == ["III", "ICI", "III"]
                assert data["key"] == {"I": "minecraft:iron_ingot", "C": center}
                assert data["result"] == {"id": f"bep_3davater_companion:{recipe}", "count": 1}
            personality = json.loads(jar.read("data/bep_3davater_companion/personalities/tortobu.json"))
            assert personality["name"] == "tortobu"
            assert personality["avatar"] == "tortobu"
            assert "ぼく" in personality["speakingStyle"] and "おっとり" in personality["instructions"]
            assert "dev/avatarcraft/player/client/AvatarRenderer.class" not in names
            assert not any(n.startswith("dev/avatarcraft/player/multiplayer/") for n in names)
        elif role == "loader":
            assert "dev/avatarcraft/api/AvatarActor.class" in jar.namelist()
            assert "dev/avatarcraft/avatar/SurfaceAtlas.class" in jar.namelist()
            assert "dev/avatarcraft/api/ExpressionKeys.class" in names
            for component in ("SeriesTabs", "SeriesScreen", "ExpressionJsonScreen", "ControlList", "ModelList"):
                assert f"dev/avatarcraft/client/{component}.class" in names
            assert "dev/avatarcraft/avatar/ExpressionShapes.class" in names
            assert jar.read("assets/bep_3davater_loader/surface.json") == (PACKAGE / "Editor/Resources/AvatarCraft/surface.json").read_bytes()
            for include in ("surface_textures", "surface_common", "surface_effects"):
                assert f"assets/bep_3davater_loader/shaders/include/{include}.glsl" in jar.namelist()
            assert not any(n.startswith("dev/avatarcraft/npc/") for n in jar.namelist())
            assert not any(n.startswith(("dev/avatarcraft/player/", "dev/avatarcraft/player/multiplayer/")) for n in names)
            assert "dev/avatarcraft/player/client/AvatarFirstPerson.class" not in names
            assert "dev/avatarcraft/player/client/AvatarRenderer.class" not in names
            assert "dev/avatarcraft/player/client/ShareClient.class" not in names
        else:
            assert "dev/avatarcraft/player/client/PlayerModelScreen.class" in names
            assert "dev/avatarcraft/player/multiplayer/PrivateAvatarHub.class" in names
            assert "dev/avatarcraft/player/client/ShareClient.class" in names
            assert "dev/avatarcraft/api/AvatarActor.class" not in names
            assert not any(n.startswith("dev/avatarcraft/npc/") for n in names)
        bundled = [n for n in names if n.endswith(".avc")]
        assert bundled == (["assets/bep_3davater_loader/avatars/tortobu.avc"] if role == "loader" else [])
        for name in names:
            if name.endswith(".class"):
                bytecode = jar.read(name)
                if role == "loader":
                    assert b"dev/avatarcraft/npc/" not in bytecode and b"dev/avatarcraft/player/" not in bytecode, name
                    assert b"dev/avatarcraft/player/multiplayer/" not in bytecode, name
                elif role == "companion":
                    assert b"dev/avatarcraft/player/" not in bytecode, name
                else:
                    assert b"dev/avatarcraft/npc/" not in bytecode, name
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
                    if source.suffix not in {".cs", ".json", ".asmdef", ".meta", ".shader"}:
                        raise ValueError(f"Unexpected Unity package file: {relative}")
                    data = source.read_bytes()
                    info = tarfile.TarInfo(f"package/{relative}")
                    info.size, info.mode, info.mtime = len(data), 0o644, 0
                    tar.addfile(info, io.BytesIO(data))
                documents = [(f"Documentation~/{p.relative_to(ROOT / 'release').as_posix()}", p.read_bytes())
                             for p in guide_files()]
                documents.append(("LICENSE.txt", license_text().encode()))
                for name, data in documents:
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
        assert METADATA["displayName"] == "B.E.P 3DAvater Exporter"
        for source in guide_files():
            assert packed[f"package/Documentation~/{source.relative_to(ROOT / 'release').as_posix()}"] == source.read_bytes()


def main():
    validate_docs()
    if DEST.exists() or ARCHIVE.exists():
        raise FileExistsError(f"Release already exists: {NAME}; preserve it or use a new version")
    jar_source = ROOT / f"build/libs/bep_3davater_loader-{MOD_VERSION}.jar"
    player_source = ROOT / f"build/libs/bep_3davater_player-{MOD_VERSION}.jar"
    npc_source = ROOT / f"build/libs/bep_3davater_companion-{MOD_VERSION}.jar"
    seen_classes = set()
    seen_packages = set()
    for role, source in (("loader", jar_source), ("player", player_source), ("companion", npc_source)):
        verify_jar(source.read_bytes(), role)
        with zipfile.ZipFile(source) as jar:
            classes = {n for n in jar.namelist() if n.endswith(".class")}
            assert not (classes & seen_classes), "Duplicate classes across mods"
            seen_classes.update(classes)
            packages = {n.rsplit("/", 1)[0] for n in classes}
            assert not (packages & seen_packages), "Split Java packages across mods"
            seen_packages.update(packages)
    OUTPUT.mkdir(exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".avatarcraft-", dir=OUTPUT) as temporary:
        stage = Path(temporary) / NAME
        (stage / "Minecraft/mods").mkdir(parents=True)
        (stage / "Unity").mkdir()
        shutil.copy2(jar_source, stage / "Minecraft/mods" / jar_source.name)
        shutil.copy2(npc_source, stage / "Minecraft/mods" / npc_source.name)
        shutil.copy2(player_source, stage / "Minecraft/mods" / player_source.name)
        (stage / "Examples/Personalities").mkdir(parents=True)
        shutil.copy2(ROOT / "src/npc/resources/data/bep_3davater_companion/personalities/tortobu.json",
                     stage / "Examples/Personalities/tortobu.json")
        for source in guide_files():
            target = stage / source.relative_to(ROOT / "release")
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
        (stage / "LICENSE.txt").write_text(license_text(), encoding="utf-8")
        make_upm(stage / f"Unity/dev.avatarcraft.exporter-{VERSION}.tgz")
        make_unitypackage(stage / f"Unity/BEP-3DAvater-Exporter-{VERSION}.unitypackage")
        for document in stage.rglob("*"):
            if document.is_file() and document.suffix in {".html", ".txt"}:
                assert not re.search(r"clonka|yilnel", document.read_text(encoding="utf-8"), re.IGNORECASE), document
        files = [{"path": p.relative_to(stage).as_posix(), "size": p.stat().st_size,
                  "sha256": sha(p.read_bytes())} for p in sorted(stage.rglob("*")) if p.is_file()]
        manifest = {"name": "B.E.P 3DAvater", "version": MOD_VERSION,
                    "exporterVersion": VERSION,
                    "documentationEntry": "index.html",
                    "minecraft": MINECRAFT_VERSION, "neoforgeTested": "26.3.0.95", "java": 25,
                    "unityTested": "2022.3.22f1", "license": "All Rights Reserved",
                    "includesAvatarAssets": True, "files": files}
        manifest["mods"] = [{"id": f"bep_3davater_{role}", "role": role,
                              "requires": [] if role == "loader" else ["bep_3davater_loader"]}
                             for role in ("loader", "player", "companion")]
        with zipfile.ZipFile(jar_source) as loader_jar:
            bundled = [n for n in loader_jar.namelist() if n.lower().endswith(".avc")]
            assert bundled == ["assets/bep_3davater_loader/avatars/tortobu.avc"], bundled
            avatar_data = loader_jar.read(bundled[0])
            with zipfile.ZipFile(io.BytesIO(avatar_data)) as model:
                model_info = json.loads(model.read("avatar.json"))
                assert model_info["name"] == "tortobu"
            assert avatar_data == (ROOT / "src/main/resources" / bundled[0]).read_bytes()
            assert not any("clonka" in n or "yilnel" in n for n in loader_jar.namelist())
            manifest["bundledAvatars"] = [{"id": "tortobu", "name": "tortobu", "author": "aoi_nasuko", "mod": "bep_3davater_loader", "entry": bundled[0], "sha256": sha(avatar_data)}]
        manifest["privatePlayerAvatarSync"] = {"protocol": "private-1", "ownerOnly": True, "cipher": "AES-256-GCM", "keyEnvelope": "RSA-OAEP-SHA256", "maxEncryptedBytes": 67108864}
        manifest["codexTested"] = "0.163.0"
        manifest["npcAuthentication"] = "Host-only Codex OAuth, OpenAI-compatible API, or Anthropic-compatible API"
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
