"""Offline UI tests: python -m unittest discover -s desktop -p test_ui.py -v."""
from __future__ import annotations

from dataclasses import dataclass
import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from types import ModuleType
import unittest
from unittest.mock import patch

os.environ["QT_QPA_PLATFORM"] = "offscreen"
HAS_QT = importlib.util.find_spec("PySide6") is not None
if HAS_QT:
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    from PySide6.QtWidgets import QApplication, QDialog
    from main import MainWindow, MediaSelectionDialog, STYLE, Worker, clean_message


@dataclass(frozen=True)
class DownloadOptions:
    url: str
    destination: Path
    platform: str = "AUTO-DETECT"
    quality: str = "BEST"
    audio_only: bool = False


@dataclass(frozen=True)
class MediaItem:
    url: str
    title: str
    kind: str
    source: str
    index: int = 1


class DownloadError(Exception):
    pass


class DownloadCancelled(DownloadError):
    pass


def fake_engine() -> ModuleType:
    engine = ModuleType("engine")
    engine.DownloadOptions = DownloadOptions
    engine.MediaItem = MediaItem
    engine.DownloadError = DownloadError
    engine.DownloadCancelled = DownloadCancelled
    return engine


@unittest.skipUnless(HAS_QT, "PySide6 belum terpasang")
class UiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.app = QApplication.instance() or QApplication([])
        cls.app.setQuitOnLastWindowClosed(False)
        cls.app.setStyleSheet(STYLE)

    def setUp(self):
        self.window = MainWindow()

    def tearDown(self):
        if self.window._thread is not None:
            self.window.cancel_work()
            self.wait_until(lambda: self.window._thread is None)
        self.window.close()
        self.app.processEvents()

    def wait_until(self, predicate, timeout=1.5):
        deadline = time.monotonic() + timeout
        while not predicate() and time.monotonic() < deadline:
            self.app.processEvents()
            time.sleep(0.005)
        self.app.processEvents()
        self.assertTrue(predicate(), "Worker/UI tidak selesai dalam batas waktu")

    def test_initial_state_and_original_audio_options(self):
        self.assertFalse(self.window.download.isEnabled())
        self.assertFalse(self.window.cancel_button.isEnabled())
        self.assertEqual(self.window.destination.text().replace("\\", "/").split("/")[-1], "BrutalDownloader")
        self.window.url.setText("https://example.com/video")
        self.window.audio.setChecked(True)
        options = self.window.options()
        self.assertTrue(options["audio_only"])
        self.assertEqual(options["quality"], "BEST")
        self.assertEqual(options["platform"], "AUTO-DETECT")
        self.assertIsInstance(options["destination"], Path)
        self.assertIn("bukan MP3", self.window.audio.text())

    def test_url_validation(self):
        for value in ("", "file:///tmp/video", "https://", "https://example.com/a b", "https://user:secret@example.com/a", "https://example.com:bad/a"):
            with self.subTest(value=value):
                self.window.url.setText(value)
                with self.assertRaises(ValueError):
                    self.window.options()

    def test_carousel_all_or_chosen(self):
        items = [MediaItem(f"https://example.com/{index}", f"Foto {index}", "image", "direct", index)
                 for index in range(1, 4)]
        dialog = MediaSelectionDialog(items, items, self.window)
        self.assertEqual(dialog.selected_items(), items)
        dialog.set_all(False)
        self.assertFalse(dialog.ok.isEnabled())
        from PySide6.QtCore import Qt
        dialog.list.item(1).setCheckState(Qt.CheckState.Checked)
        self.assertEqual(dialog.selected_items(), [items[1]])
        self.assertTrue(dialog.ok.isEnabled())
        dialog.set_all(True)
        self.assertEqual(dialog.selected_items(), items)
        dialog.close()

    def test_inspection_runs_off_ui_thread_and_invalidates(self):
        engine = fake_engine()
        items = [MediaItem("https://example.com/media", "Judul", "video", "ytdlp")]
        calls = []

        def inspect(options, cancel, log):
            calls.append((options, cancel, threading.current_thread()))
            log("Metadata tersedia")
            return items

        engine.inspect_media = inspect
        self.window.url.setText("https://example.com/video")
        with patch.dict(sys.modules, {"engine": engine}), patch.object(MediaSelectionDialog, "exec", return_value=QDialog.DialogCode.Accepted):
            self.window.inspect_url()
            self.wait_until(lambda: self.window._thread is None)
        self.assertIsInstance(calls[0][0], DownloadOptions)
        self.assertIsNot(calls[0][2], threading.main_thread())
        self.assertEqual(self.window._selected, items)
        self.assertTrue(self.window.download.isEnabled())
        self.assertIn("Metadata tersedia", self.window.console.toPlainText())
        self.window.audio.setChecked(True)
        self.assertFalse(self.window.download.isEnabled())
        self.assertEqual(self.window._selected, [])
        self.assertIn("Metadata tersedia", self.window.console.toPlainText())

    def test_download_contract_passes_selected_items_and_paths(self):
        engine = fake_engine()
        items = [MediaItem("https://example.com/2", "Pilihan kedua", "image", "direct", 2)]
        received = []

        def download(options, selected, cancel, progress, log):
            received.append((options, selected, cancel))
            progress(60, "Menyimpan media")
            log("Unduhan berjalan")
            return [options.destination / "hasil.jpg"]

        engine.download_media = download
        self.window.url.setText("https://example.com/post")
        values = self.window.options()
        results, progress = [], []
        worker = Worker("download", values, items, threading.Event())
        worker.result.connect(results.append)
        worker.progress.connect(lambda percent, message: progress.append((percent, message)))
        # The fake backend does not create files or directories.
        with patch.dict(sys.modules, {"engine": engine}):
            worker.run()
        self.assertEqual(received[0][1], items)
        self.assertIsInstance(received[0][0], DownloadOptions)
        self.assertEqual(results, [[values["destination"] / "hasil.jpg"]])
        self.assertEqual(progress, [(60, "Menyimpan media")])

    def test_rejecting_initial_selection_does_not_enable_download(self):
        engine = fake_engine()
        engine.inspect_media = lambda options, cancel, log: [MediaItem("https://example.com/media", "Media", "video", "direct")]
        self.window.url.setText("https://example.com/video")
        with patch.dict(sys.modules, {"engine": engine}), patch.object(MediaSelectionDialog, "exec", return_value=QDialog.DialogCode.Rejected):
            self.window.inspect_url()
            self.wait_until(lambda: self.window._thread is None)
        self.assertFalse(self.window.download.isEnabled())
        self.assertTrue(self.window.choose.isEnabled())
        self.assertEqual(self.window._selected, [])

    def test_download_workflow_keeps_console_and_finishes(self):
        engine = fake_engine()
        item = MediaItem("https://example.com/media", "Media", "video", "direct")
        self.window.url.setText("https://example.com/video")
        self.window._inspected = self.window.options()
        self.window._items = [item]
        self.window._selected = [item]

        def download(options, selected, cancel, progress, log):
            self.assertEqual(selected, [item])
            progress(100, "Selesai")
            return [options.destination / "media.mp4"]

        engine.download_media = download
        before = self.window.console.toPlainText()
        with patch.dict(sys.modules, {"engine": engine}):
            self.window.download_selected()
            self.wait_until(lambda: self.window._thread is None)
        self.assertEqual(self.window.status.text(), "SELESAI")
        self.assertEqual(self.window.progress.value(), 100)
        self.assertIn(before, self.window.console.toPlainText())
        self.assertIn("media.mp4", self.window.console.toPlainText())

    def test_close_defers_until_worker_finishes_without_blocking(self):
        engine = fake_engine()
        entered, release = threading.Event(), threading.Event()

        def inspect(options, cancel, log):
            entered.set()
            release.wait(2)
            raise DownloadCancelled("Dibatalkan")

        engine.inspect_media = inspect
        self.window.show()
        self.window.url.setText("https://example.com/video")
        with patch.dict(sys.modules, {"engine": engine}):
            try:
                self.window.inspect_url()
                self.wait_until(entered.is_set)
                start = time.monotonic()
                self.window.close()
                self.assertLess(time.monotonic() - start, 0.2)
                self.assertTrue(self.window._cancel.is_set())
                self.assertTrue(self.window.isVisible())
                self.assertIsNotNone(self.window._thread)
                self.assertTrue(self.window._closing)
            finally:
                release.set()
                self.wait_until(lambda: self.window._thread is None)
                self.wait_until(lambda: not self.window.isVisible())

    def test_errors_hide_tracebacks(self):
        self.assertNotIn("Traceback", clean_message("Traceback (most recent call last):\nsecret stack"))
        engine = fake_engine()

        def inspect(options, cancel, log):
            raise RuntimeError("private implementation detail")

        engine.inspect_media = inspect
        self.window.url.setText("https://example.com/video")
        worker = Worker("inspect", self.window.options(), [], threading.Event())
        errors = []
        worker.failed.connect(lambda message, cancelled: errors.append((message, cancelled)))
        with patch.dict(sys.modules, {"engine": engine}):
            worker.run()
        self.assertEqual(len(errors), 1)
        self.assertFalse(errors[0][1])
        self.assertNotIn("private implementation detail", errors[0][0])

    def test_cli_smoke_is_offline_and_exits_within_two_seconds(self):
        result = subprocess.run(
            [sys.executable, str(Path(__file__).with_name("main.py")), "--smoke-test"],
            capture_output=True, text=True, timeout=2,
        )
        self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
