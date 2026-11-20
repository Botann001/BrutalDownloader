"""Build a Windows x64 executable and a ZIP with dependency license notices."""

from __future__ import annotations

import hashlib
import importlib.metadata
import os
from pathlib import Path
import platform
import shutil
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parent
NAME = "Brutal Downloader"


def main() -> int:
    if sys.platform != "win32" or platform.machine().upper() not in ("AMD64", "X86_64"):
        raise SystemExit("Build portable harus dijalankan di Windows x64 dengan Python x64.")
    dist = ROOT / "dist"
    build = ROOT / "build"
    dist.mkdir(exist_ok=True)
    build.mkdir(exist_ok=True)
    icon = build / "brutal.ico"
    # Generate our own application artwork; no external assets or fonts required.
    os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")
    from PySide6.QtCore import QPoint, QRect, Qt
    from PySide6.QtGui import QColor, QGuiApplication, QImage, QPainter, QPolygon

    _app = QGuiApplication.instance() or QGuiApplication([])
    image = QImage(256, 256, QImage.Format.Format_ARGB32)
    image.fill(QColor("#101010"))
    painter = QPainter(image)
    painter.fillRect(QRect(12, 12, 232, 232), QColor("#FFE600"))
    painter.fillRect(QRect(24, 24, 208, 208), QColor("#101010"))
    painter.setPen(Qt.PenStyle.NoPen)
    painter.setBrush(QColor("#FF5722"))
    painter.drawPolygon(QPolygon([QPoint(108, 48), QPoint(148, 48), QPoint(148, 126),
                                  QPoint(184, 126), QPoint(128, 184), QPoint(72, 126),
                                  QPoint(108, 126)]))
    painter.fillRect(QRect(62, 196, 132, 14), QColor("#FFE600"))
    painter.end()
    if not image.save(str(icon), "ICO"):
        raise SystemExit("Gagal membuat ikon aplikasi.")

    subprocess.run([
        sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean",
        "--onefile", "--windowed", "--name", NAME,
        "--distpath", str(dist), "--workpath", str(build / "pyinstaller"),
        "--specpath", str(build), "--icon", str(icon),
        "--hidden-import", "engine", "--collect-submodules", "yt_dlp",
        "--copy-metadata", "yt-dlp", str(ROOT / "main.py"),
    ], cwd=ROOT, check=True)
    executable = dist / f"{NAME}.exe"
    # Test the actual frozen artifact, not just the source entry point.
    subprocess.run([str(executable), "--smoke-test"], cwd=dist, check=True, timeout=90)
    subprocess.run([str(executable), "--check-backend"], cwd=dist, check=True, timeout=90)

    licenses = build / "licenses"
    if licenses.exists():
        shutil.rmtree(licenses)
    licenses.mkdir()
    # Include installed dependency licenses alongside our human-readable notice.
    for distribution in importlib.metadata.distributions():
        name = distribution.metadata.get("Name", "dependency")
        if name.lower() in {"pip", "setuptools", "altgraph", "pefile",
                             "pyinstaller-hooks-contrib", "pywin32-ctypes", "packaging"}:
            continue
        for file in distribution.files or ():
            relative = Path(str(file))
            if not any(part.lower().startswith(("license", "licence", "copying", "notice"))
                       for part in relative.parts):
                continue
            source = Path(str(distribution.locate_file(file)))
            if source.is_file():
                target = licenses / name / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, target)
    python_license = Path(sys.base_prefix) / "LICENSE.txt"
    if not python_license.is_file():
        raise SystemExit("Lisensi Python tidak ditemukan; paket distribusi tidak dibuat.")
    (licenses / "Python").mkdir(exist_ok=True)
    shutil.copy2(python_license, licenses / "Python" / "LICENSE.txt")
    for name in ("LGPL-3.0-only.txt", "GPL-3.0-only.txt"):
        target = licenses / "Qt" / name
        target.parent.mkdir(exist_ok=True)
        shutil.copy2(ROOT / "licenses" / name, target)
    archive = dist / "BrutalDownloader-Windows-x64-portable.zip"
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        bundle.write(executable, executable.name)
        bundle.write(ROOT / "README.md", "README.md")
        bundle.write(ROOT / "THIRD_PARTY_NOTICES.md", "THIRD_PARTY_NOTICES.md")
        for name in ("main.py", "engine.py", "requirements.txt", "requirements-build.txt",
                     "build_portable.py", "test_engine.py", "test_ui.py", "README.md",
                     "THIRD_PARTY_NOTICES.md"):
            bundle.write(ROOT / name, "source/" + name)
        for file in (ROOT / "licenses").iterdir():
            if file.is_file():
                bundle.write(file, "source/licenses/" + file.name)
        for file in licenses.rglob("*"):
            if file.is_file():
                bundle.write(file, "licenses/" + file.relative_to(licenses).as_posix())
    checksum = hashlib.sha256(archive.read_bytes()).hexdigest()
    (dist / (archive.name + ".sha256")).write_text(f"{checksum}  {archive.name}\n", encoding="utf-8")
    print(f"\nPortable: {executable}\nDistribusi: {archive}\nSHA256: {checksum}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
