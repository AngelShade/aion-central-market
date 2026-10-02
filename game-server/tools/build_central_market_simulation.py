"""Compile and stage the simulation without replacing a running server's JAR."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RUNTIME_FILES = [
    "config/main/central-market-simulation.properties",
    "config/central-market/schema.sql",
    "config/central-market/media/market.html",
    "config/central-market/media/market.js",
    "config/central-market/media/market.css",
]
SOURCES = [
    "configs/main/CentralMarketSimulationConfig.java", "configs/Config.java",
    "dao/InventoryDAO.java", "services/CentralMarketSimulation.java", "services/CentralMarketSettlement.java", "services/CentralMarketService.java",
]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--server", type=Path, default=ROOT / "target-deploy/game-server")
    parser.add_argument("--output", type=Path, default=ROOT / "game-server/target/central-market-simulation/package")
    args = parser.parse_args()
    server, out = args.server.resolve(), args.output.resolve()
    classes = out.parent / "runtime-classes"
    classes.mkdir(parents=True, exist_ok=True)
    subprocess.run(["javac", "-encoding", "UTF-8", "-cp", str(server / "libs/*"), "-d", str(classes),
                    *[str(ROOT / "game-server/src/com/aionemu/gameserver" / s) for s in SOURCES]], check=True)
    payload = out / "payload"
    jar_path = Path("libs/game-server-4.8-SNAPSHOT.jar")
    (payload / jar_path).parent.mkdir(parents=True, exist_ok=True)
    replacements = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob("*.class")}
    with zipfile.ZipFile(server / jar_path) as original, zipfile.ZipFile(payload / jar_path, "w", zipfile.ZIP_DEFLATED) as patched:
        for info in original.infolist():
            if info.filename not in replacements:
                patched.writestr(info, original.read(info.filename))
        for name, contents in replacements.items():
            patched.writestr(name, contents)
    for name in RUNTIME_FILES:
        target = payload / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / "game-server" / name, target)
    manifest = []
    for name in [jar_path.as_posix(), *RUNTIME_FILES]:
        before = server / name
        manifest.append({"path": name, "sha256": digest(payload / name),
                         "before": digest(before) if before.exists() else None})
    (out / "manifest.json").write_text(json.dumps(manifest, indent=2), encoding="utf-8")
    shutil.copy2(ROOT / "game-server/tools/InstallCentralMarketSimulation.ps1", out / "Install.ps1")
    shutil.copy2(ROOT / "docs/CENTRAL_MARKET.md", out / "CENTRAL_MARKET.md")
    results = ROOT / "game-server/target/central-market-validation/database-results.txt"
    if results.exists():
        shutil.copy2(results, out / "database-results.txt")
    (out / "README.txt").write_text(
        "Central Market simulation - 3,000 simulated traders by default.\n"
        "Stop GameServer gracefully before installing. The installer refuses a listening game port.\n"
        'Run: powershell -ExecutionPolicy Bypass -File Install.ps1 -GameServerRoot "' + str(server) + '"\n'
        "The installer verifies source and destination hashes, backs up replacements, and rolls back on failure.\n"
        "Start GameServer normally, reopen Central Market, buy an item and sell a listing, then collect items and Kinah beside each order and withdraw.\n"
        "The full catalog warms up over the first few minutes. Stock matches before on-demand quotes rotate.\n"
        "Only the six manifest files are replaced. Existing icons and client modifications are preserved.\n",
        encoding="utf-8")
    with zipfile.ZipFile(out.parent / "central-market-simulation.zip", "w", zipfile.ZIP_DEFLATED) as archive:
        for p in out.rglob("*"):
            if p.is_file():
                archive.write(p, p.relative_to(out))
    print(f"Compiled {len(replacements)} runtime classes; verified package staged at {out}")


if __name__ == "__main__":
    main()
