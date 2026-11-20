"""Desktop client. Run `python main.py --smoke-test` for an offline UI check."""

from __future__ import annotations

import importlib
import importlib.util
import os
import sys
import threading
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit

if "--smoke-test" in sys.argv:
    os.environ["QT_QPA_PLATFORM"] = "offscreen"

from PySide6.QtCore import (
    QObject,
    QStandardPaths,
    Qt,
    QThread,
    QTimer,
    QUrl,
    Signal,
    Slot,
)
from PySide6.QtGui import QCloseEvent, QDesktopServices, QFont
from PySide6.QtWidgets import (
    QApplication,
    QButtonGroup,
    QCheckBox,
    QComboBox,
    QDialog,
    QDialogButtonBox,
    QFileDialog,
    QFrame,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QListWidget,
    QListWidgetItem,
    QMainWindow,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QRadioButton,
    QScrollArea,
    QSizePolicy,
    QSplitter,
    QVBoxLayout,
    QWidget,
)


STYLE = """
QWidget { background: #101010; color: #EEEEEE; font-family: Consolas, monospace; font-size: 12px; }
QLabel { background: transparent; }
QLabel#brand { color: #FFE600; font-size: 23px; font-weight: bold; }
QLabel#title { font-size: 25px; font-weight: bold; }
QLabel#muted { color: #AAAAAA; }
QLabel#section { color: #FFE600; font-weight: bold; }
QLabel#status { color: #FFE600; border: 1px solid #555; padding: 6px 10px; }
QFrame#sidebar { border-right: 2px solid #FFE600; }
QFrame#card { background: #191919; border: 1px solid #444; }
QLineEdit, QComboBox, QListWidget, QPlainTextEdit {
    background: #191919; border: 1px solid #626262; padding: 9px; selection-background-color: #FFE600;
    selection-color: #101010;
}
QLineEdit:focus, QComboBox:focus, QListWidget:focus { border: 1px solid #FFE600; }
QComboBox::drop-down { border: 0; width: 24px; }
QComboBox QAbstractItemView { background: #191919; selection-background-color: #FFE600; selection-color: #101010; }
QPushButton { background: #242424; border: 1px solid #777; padding: 10px 14px; font-weight: bold; }
QPushButton:hover { background: #363636; border-color: #FFE600; }
QPushButton:pressed { background: #555; }
QPushButton#primary { color: #101010; background: #FFE600; border: 2px solid #FFE600; }
QPushButton#primary:hover { background: #FFF36A; }
QPushButton#cancel { color: #FF5722; border-color: #FF5722; }
QPushButton:disabled { color: #777; background: #202020; border-color: #3A3A3A; }
QCheckBox, QRadioButton { spacing: 7px; background: transparent; }
QCheckBox::indicator, QRadioButton::indicator { width: 15px; height: 15px; border: 1px solid #888; background: #191919; }
QCheckBox::indicator:checked, QRadioButton::indicator:checked { background: #FFE600; border: 2px solid #FFE600; }
QProgressBar { background: #191919; border: 1px solid #666; min-height: 20px; text-align: center; }
QProgressBar::chunk { background: #FF5722; }
QSplitter::handle { background: #444; height: 3px; }
QScrollArea { border: none; }
QToolTip { background: #FFE600; color: #101010; border: 1px solid #101010; }
"""


def label(text: str, name: str = "", wrap: bool = False) -> QLabel:
    widget = QLabel(text)
    widget.setObjectName(name)
    widget.setWordWrap(wrap)
    widget.setTextFormat(Qt.TextFormat.PlainText)
    return widget


def clean_message(value: Any) -> str:
    """Keep backend errors useful, without rendering stack traces or rich text."""
    text = str(value).strip()
    if "traceback" in text.lower():
        return "Backend melaporkan kesalahan. Periksa URL, koneksi, dan dependensi."
    return (text.splitlines()[0][:500] if text else "Operasi tidak dapat diselesaikan.")


class Worker(QObject):
    result = Signal(object)
    failed = Signal(str, bool)
    progress = Signal(int, str)
    log = Signal(str)
    finished = Signal()

    def __init__(self, operation: str, values: dict[str, Any], items: list[Any], cancel: threading.Event):
        super().__init__()
        self.operation = operation
        self.values = values.copy()
        self.items = list(items)
        self.cancel = cancel

    @Slot()
    def run(self) -> None:
        engine = None
        try:
            # Importing extractors and all network/filesystem work stays off the UI thread.
            engine = importlib.import_module("engine")
            options = engine.DownloadOptions(**self.values)
            if self.cancel.is_set():
                raise engine.DownloadCancelled("Operasi dibatalkan.")
            if self.operation == "inspect":
                result = engine.inspect_media(options, self.cancel, self.log.emit)
            else:
                result = engine.download_media(
                    options, self.items, self.cancel, self.progress.emit, self.log.emit,
                )
            if self.cancel.is_set():
                raise engine.DownloadCancelled("Operasi dibatalkan.")
            self.result.emit(result)
        except Exception as exc:
            if engine is not None and isinstance(exc, engine.DownloadCancelled):
                self.failed.emit("Operasi dibatalkan.", True)
            elif engine is not None and isinstance(exc, engine.DownloadError):
                self.failed.emit(clean_message(exc), False)
            elif isinstance(exc, ImportError):
                self.failed.emit("Backend atau dependensi belum tersedia. Periksa instalasi desktop dan engine.py.", False)
            else:
                self.failed.emit("Operasi gagal. Periksa URL, izin folder, koneksi, dan dependensi backend.", False)
        finally:
            self.finished.emit()


class MediaSelectionDialog(QDialog):
    def __init__(self, items: list[Any], selected: list[Any], parent: QWidget):
        super().__init__(parent)
        self.setWindowTitle("Pilih media / carousel")
        self.resize(640, 430)
        self.items = items
        layout = QVBoxLayout(self)
        layout.addWidget(label("PILIH MEDIA", "section"))
        layout.addWidget(label("Centang semua atau hanya item yang ingin diunduh.", "muted", True))
        controls = QHBoxLayout()
        all_button = QPushButton("Pilih semua")
        none_button = QPushButton("Kosongkan")
        controls.addWidget(all_button)
        controls.addWidget(none_button)
        controls.addStretch()
        layout.addLayout(controls)
        self.list = QListWidget()
        self.list.setWordWrap(True)
        for media in items:
            row = QListWidgetItem(f"{media.index:02d}  [{media.kind}] {media.title}\n     Sumber: {media.source}")
            row.setFlags(row.flags() | Qt.ItemFlag.ItemIsUserCheckable)
            row.setCheckState(Qt.CheckState.Checked if media in selected else Qt.CheckState.Unchecked)
            row.setToolTip(str(media.url))
            self.list.addItem(row)
        layout.addWidget(self.list)
        self.count = label("", "muted")
        layout.addWidget(self.count)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Ok | QDialogButtonBox.StandardButton.Cancel)
        self.ok = buttons.button(QDialogButtonBox.StandardButton.Ok)
        self.ok.setText("Gunakan pilihan")
        buttons.button(QDialogButtonBox.StandardButton.Cancel).setText("Batal")
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        layout.addWidget(buttons)
        all_button.clicked.connect(lambda: self.set_all(True))
        none_button.clicked.connect(lambda: self.set_all(False))
        self.list.itemChanged.connect(self.update_count)
        self.update_count()

    def selected_items(self) -> list[Any]:
        return [item for index, item in enumerate(self.items)
                if self.list.item(index).checkState() == Qt.CheckState.Checked]

    def set_all(self, checked: bool) -> None:
        for index in range(self.list.count()):
            self.list.item(index).setCheckState(Qt.CheckState.Checked if checked else Qt.CheckState.Unchecked)

    def update_count(self) -> None:
        count = len(self.selected_items())
        self.count.setText(f"{count} dari {len(self.items)} item dipilih")
        self.ok.setEnabled(count > 0)


class MainWindow(QMainWindow):
    def __init__(self):
        super().__init__()
        self.setWindowTitle("Brutal Downloader")
        self.resize(1100, 850)
        self.setMinimumSize(660, 620)
        self._thread: QThread | None = None
        self._worker: Worker | None = None
        self._cancel = threading.Event()
        self._closing = False
        self._operation = ""
        self._result: Any = None
        self._failure: tuple[str, bool] | None = None
        self._items: list[Any] = []
        self._selected: list[Any] = []
        self._inspected: dict[str, Any] | None = None
        self._last_destination: Path | None = None
        self._build_ui()
        self._update_controls()
        self.append_log("Siap. Tempel URL, inspeksi media, lalu unduh pilihan Anda.")

    def _build_ui(self) -> None:
        root = QWidget()
        self.setCentralWidget(root)
        shell = QHBoxLayout(root)
        shell.setContentsMargins(0, 0, 0, 0)
        shell.setSpacing(0)
        self.sidebar = QFrame()
        self.sidebar.setObjectName("sidebar")
        self.sidebar.setFixedWidth(205)
        side = QVBoxLayout(self.sidebar)
        side.setContentsMargins(20, 30, 20, 22)
        side.addWidget(label("BRUTAL\nDOWNLOADER", "brand"))
        side.addSpacing(20)
        side.addWidget(label("01 / TEMPEL URL\n\n02 / INSPEKSI\n\n03 / PILIH MEDIA\n\n04 / UNDUH", "muted"))
        side.addStretch()
        side.addWidget(label("TANPA KONVERSI.\nFILE DARI SUMBER.", "section", True))
        side.addSpacing(12)
        side.addWidget(label("Unduh hanya konten yang Anda miliki atau diizinkan untuk disimpan.", "muted", True))
        shell.addWidget(self.sidebar)
        content = QVBoxLayout()
        content.setContentsMargins(22, 22, 22, 18)
        content.setSpacing(14)
        shell.addLayout(content, 1)
        header = QHBoxLayout()
        heading = QVBoxLayout()
        heading.addWidget(label("AMBIL. SIMPAN. SELESAI.", "title"))
        heading.addWidget(label("Media downloader / desktop", "muted"))
        header.addLayout(heading, 1)
        about = QPushButton("Tentang")
        about.clicked.connect(self.show_about)
        header.addWidget(about, 0, Qt.AlignmentFlag.AlignTop)
        content.addLayout(header)
        splitter = QSplitter(Qt.Orientation.Vertical)
        content.addWidget(splitter, 1)
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        scroll.setHorizontalScrollBarPolicy(Qt.ScrollBarPolicy.ScrollBarAlwaysOff)
        form = QWidget()
        layout = QVBoxLayout(form)
        layout.setContentsMargins(0, 0, 8, 4)
        layout.setSpacing(12)
        layout.addWidget(label("01 / TARGET URL", "section"))
        url_row = QHBoxLayout()
        self.url = QLineEdit()
        self.url.setPlaceholderText("https://…")
        self.url.setAccessibleName("URL media")
        self.paste = QPushButton("Tempel")
        self.clear = QPushButton("Hapus")
        self.paste.clicked.connect(self.paste_url)
        self.clear.clicked.connect(self.url.clear)
        url_row.addWidget(self.url, 1)
        url_row.addWidget(self.paste)
        url_row.addWidget(self.clear)
        layout.addLayout(url_row)
        layout.addWidget(label("Inspeksi/unduh dapat mengirim URL ke resolver pihak ketiga SnapSave / curl-x.", "muted", True))
        settings = QHBoxLayout()
        platform_col = QVBoxLayout()
        platform_col.addWidget(label("PLATFORM", "section"))
        self.platform = QComboBox()
        for title, value in [("Deteksi otomatis", "AUTO-DETECT"), ("YouTube", "YOUTUBE"),
                             ("Instagram", "INSTAGRAM"), ("TikTok", "TIKTOK"), ("Tautan langsung", "DIRECT LINK")]:
            self.platform.addItem(title, value)
        self.platform.setAccessibleName("Platform")
        platform_col.addWidget(self.platform)
        settings.addLayout(platform_col, 1)
        quality_col = QVBoxLayout()
        quality_col.addWidget(label("KUALITAS", "section"))
        self.quality = QWidget()
        quality_buttons = QHBoxLayout(self.quality)
        quality_buttons.setContentsMargins(0, 9, 0, 9)
        quality_buttons.setSpacing(10)
        self.quality_group = QButtonGroup(self)
        for text in ("BEST", "720", "480", "360"):
            button = QRadioButton(text)
            button.setAccessibleName(f"Kualitas {text}")
            self.quality_group.addButton(button)
            quality_buttons.addWidget(button)
            if text == "BEST":
                button.setChecked(True)
        quality_col.addWidget(self.quality)
        settings.addLayout(quality_col, 1)
        layout.addLayout(settings)
        self.audio = QCheckBox("Audio saja — audio asli, bukan MP3")
        layout.addWidget(self.audio)
        layout.addWidget(label("YouTube: hanya kualitas gabungan video + audio yang tersedia. Kualitas adalah preferensi; tanpa janji konversi atau ffmpeg.", "muted", True))
        layout.addWidget(label("02 / FOLDER TUJUAN", "section"))
        folder_row = QHBoxLayout()
        self.destination = QLineEdit()
        downloads = QStandardPaths.writableLocation(QStandardPaths.StandardLocation.DownloadLocation)
        self.destination.setText(str((Path(downloads) if downloads else Path.home() / "Downloads") / "BrutalDownloader"))
        self.destination.setAccessibleName("Folder tujuan unduhan")
        self.browse = QPushButton("Pilih folder")
        self.browse.clicked.connect(self.choose_folder)
        folder_row.addWidget(self.destination, 1)
        folder_row.addWidget(self.browse)
        layout.addLayout(folder_row)
        self.selection = label("Belum ada media. Inspeksi URL terlebih dahulu.", "muted", True)
        layout.addWidget(self.selection)
        actions = QHBoxLayout()
        self.inspect = QPushButton("1. Inspeksi")
        self.choose = QPushButton("Pilih media")
        self.download = QPushButton("2. Unduh")
        self.download.setObjectName("primary")
        self.inspect.clicked.connect(self.inspect_url)
        self.choose.clicked.connect(self.choose_media)
        self.download.clicked.connect(self.download_selected)
        actions.addWidget(self.inspect)
        actions.addWidget(self.choose)
        actions.addWidget(self.download, 1)
        layout.addLayout(actions)
        layout.addStretch()
        scroll.setWidget(form)
        splitter.addWidget(scroll)
        console_panel = QWidget()
        console_layout = QVBoxLayout(console_panel)
        console_layout.setContentsMargins(0, 10, 0, 0)
        console_layout.addWidget(label("03 / KONSOL AKTIVITAS", "section"))
        self.console = QPlainTextEdit()
        self.console.setReadOnly(True)
        self.console.setPlaceholderText("Aktivitas backend akan muncul di sini.")
        self.console.setMinimumHeight(85)
        self.console.setAccessibleName("Konsol aktivitas")
        console_layout.addWidget(self.console)
        splitter.addWidget(console_panel)
        splitter.setSizes([510, 160])
        splitter.setChildrenCollapsible(False)
        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.progress.setValue(0)
        content.addWidget(self.progress)
        self.detail = label("Menunggu URL.", "muted", True)
        content.addWidget(self.detail)
        footer = QHBoxLayout()
        self.status = label("SIAP", "status")
        self.status.setSizePolicy(QSizePolicy.Policy.Maximum, QSizePolicy.Policy.Preferred)
        self.cancel_button = QPushButton("Batalkan")
        self.cancel_button.setObjectName("cancel")
        self.cancel_button.clicked.connect(self.cancel_work)
        self.open_button = QPushButton("Buka tujuan")
        self.open_button.clicked.connect(self.open_destination)
        footer.addWidget(self.status)
        footer.addStretch()
        footer.addWidget(self.cancel_button)
        footer.addWidget(self.open_button)
        content.addLayout(footer)
        self.url.textChanged.connect(self.invalidate_inspection)
        self.platform.currentIndexChanged.connect(self.invalidate_inspection)
        self.quality_group.buttonClicked.connect(self.invalidate_inspection)
        self.audio.toggled.connect(self.invalidate_inspection)

    def resizeEvent(self, event: Any) -> None:
        if hasattr(self, "sidebar"):
            self.sidebar.setVisible(self.width() >= 980)
        super().resizeEvent(event)

    @Slot()
    def paste_url(self) -> None:
        self.url.setText(QApplication.clipboard().text().strip())

    @Slot()
    def choose_folder(self) -> None:
        folder = QFileDialog.getExistingDirectory(self, "Pilih folder tujuan", self.destination.text())
        if folder:
            self.destination.setText(folder)

    def options(self) -> dict[str, Any]:
        url = self.url.text().strip()
        try:
            parsed = urlsplit(url)
            valid = parsed.scheme.lower() in ("http", "https") and bool(parsed.hostname) and not any(c.isspace() or c == "\\" or ord(c) < 32 or ord(c) == 127 for c in url)
            if parsed.username is not None or parsed.password is not None:
                valid = False
            _ = parsed.port  # Validate malformed ports before starting a worker.
        except ValueError:
            valid = False
        if not valid:
            raise ValueError("Masukkan URL HTTP/HTTPS yang valid tanpa kredensial atau spasi.")
        folder = self.destination.text().strip()
        if not folder:
            raise ValueError("Pilih folder tujuan terlebih dahulu.")
        if "\x00" in folder:
            raise ValueError("Folder tujuan tidak valid.")
        return {
            "url": url,
            "destination": Path(folder).expanduser(),
            "platform": self.platform.currentData(),
            "quality": self.quality_group.checkedButton().text(),
            "audio_only": self.audio.isChecked(),
        }

    def validated_options(self) -> dict[str, Any] | None:
        try:
            return self.options()
        except (ValueError, OSError) as exc:
            QMessageBox.warning(self, "Periksa input", clean_message(exc))
            return None

    @Slot()
    def invalidate_inspection(self) -> None:
        self._items = []
        self._selected = []
        self._inspected = None
        self.selection.setText("Pengaturan berubah. Inspeksi URL untuk memperbarui media.")
        self._update_controls()

    @Slot()
    def inspect_url(self) -> None:
        if self._thread is not None:
            return
        values = self.validated_options()
        if values is not None:
            self.invalidate_inspection()
            self._inspected = values.copy()
            self.start_work("inspect", values, [])

    @Slot()
    def choose_media(self) -> None:
        if not self._items or self._thread is not None or self._closing:
            return
        dialog = MediaSelectionDialog(self._items, self._selected or self._items, self)
        if dialog.exec() == QDialog.DialogCode.Accepted and not self._closing:
            self._selected = dialog.selected_items()
        self.selection.setText(f"{len(self._selected)} dari {len(self._items)} media dipilih. Klik Unduh untuk menyimpan.")
        self._update_controls()

    @Slot()
    def download_selected(self) -> None:
        if self._thread is not None or not self._selected:
            return
        values = self.validated_options()
        if values is None:
            return
        if self._inspected is None or any(values[key] != self._inspected[key] for key in ("url", "platform", "quality", "audio_only")):
            self.invalidate_inspection()
            QMessageBox.information(self, "Inspeksi ulang", "Pengaturan media berubah. Inspeksi URL kembali sebelum mengunduh.")
            return
        self.start_work("download", values, self._selected)

    def start_work(self, operation: str, values: dict[str, Any], items: list[Any]) -> None:
        self._operation = operation
        self._result = None
        self._failure = None
        self._cancel = threading.Event()
        self._last_destination = values["destination"]
        thread = QThread(self)
        worker = Worker(operation, values, items, self._cancel)
        self._thread = thread
        self._worker = worker
        worker.moveToThread(thread)
        thread.started.connect(worker.run)
        worker.result.connect(self.receive_result)
        worker.failed.connect(self.receive_failure)
        worker.log.connect(self.append_log)
        worker.progress.connect(self.update_progress)
        worker.finished.connect(thread.quit)
        worker.finished.connect(worker.deleteLater)
        thread.finished.connect(self.work_finished)
        thread.finished.connect(thread.deleteLater)
        self.progress.setRange(0, 0)
        self.status.setText("INSPEKSI" if operation == "inspect" else "MENGUNDUH")
        self.detail.setText("Memeriksa media…" if operation == "inspect" else "Mengunduh media terpilih…")
        self.append_log("--- Inspeksi dimulai ---" if operation == "inspect" else f"--- Unduh {len(items)} media ---")
        self._update_controls()
        thread.start()

    @Slot(object)
    def receive_result(self, result: Any) -> None:
        self._result = result

    @Slot(str, bool)
    def receive_failure(self, message: str, cancelled: bool) -> None:
        self._failure = (message, cancelled)

    @Slot(int, str)
    def update_progress(self, percent: int, message: str) -> None:
        if percent < 0:
            self.progress.setRange(0, 0)
        else:
            self.progress.setRange(0, 100)
            self.progress.setValue(max(0, min(100, percent)))
        self.detail.setText(clean_message(message))

    @Slot(str)
    def append_log(self, message: str) -> None:
        self.console.appendPlainText(clean_message(message))
        bar = self.console.verticalScrollBar()
        bar.setValue(bar.maximum())

    @Slot()
    def work_finished(self) -> None:
        # This slot runs only after QThread.finished; never drop a running thread.
        self._thread = None
        self._worker = None
        self.progress.setRange(0, 100)
        self._update_controls()
        if self._closing:
            QTimer.singleShot(0, self.close)
            return
        if self._failure is not None or self._cancel.is_set():
            message, cancelled = self._failure or ("Operasi dibatalkan.", True)
            self.status.setText("DIBATALKAN" if cancelled else "GAGAL")
            self.detail.setText(message)
            self.progress.setValue(0)
            self.append_log(message)
            if not cancelled:
                QMessageBox.warning(self, "Operasi gagal", message)
            return
        if self._operation == "inspect":
            self._items = list(self._result or [])
            self._selected = []
            self.progress.setValue(0)
            self.status.setText("TERINSPEKSI")
            self.detail.setText(f"Ditemukan {len(self._items)} media.")
            self.append_log(self.detail.text())
            if self._items:
                self.choose_media()
            else:
                self.selection.setText("Tidak ada media yang dapat diunduh dari URL ini.")
                QMessageBox.information(self, "Media tidak ditemukan", self.selection.text())
        else:
            paths = list(self._result or [])
            self.progress.setValue(100 if paths else 0)
            self.status.setText("SELESAI" if paths else "TANPA FILE")
            self.detail.setText(f"{len(paths)} file tersimpan." if paths else "Backend tidak mengembalikan file tersimpan.")
            for path in paths:
                self.append_log(f"Tersimpan: {path}")
            self.append_log(self.detail.text())
        self._update_controls()

    def _update_controls(self) -> None:
        busy = self._thread is not None
        for widget in (self.url, self.paste, self.clear, self.platform, self.quality,
                       self.audio, self.destination, self.browse, self.inspect):
            widget.setEnabled(not busy and not self._closing)
        self.choose.setEnabled(not busy and bool(self._items) and not self._closing)
        self.download.setEnabled(not busy and bool(self._selected) and not self._closing)
        self.cancel_button.setEnabled(busy and not self._cancel.is_set())
        self.open_button.setEnabled(not self._closing)

    @Slot()
    def cancel_work(self) -> None:
        if self._thread is not None:
            self._cancel.set()
            self.status.setText("MEMBATALKAN")
            self.detail.setText("Menunggu backend berhenti dengan aman…")
            self.append_log("Pembatalan diminta; operasi jaringan yang aktif mungkin perlu waktu untuk berhenti.")
            self._update_controls()

    @Slot()
    def open_destination(self) -> None:
        destination = self._last_destination
        if self._thread is None:
            text = self.destination.text().strip()
            if not text:
                QMessageBox.warning(self, "Folder tujuan", "Pilih folder tujuan terlebih dahulu.")
                return
            destination = Path(text).expanduser()
        if destination is not None and not QDesktopServices.openUrl(QUrl.fromLocalFile(str(destination.absolute()))):
            QMessageBox.warning(self, "Folder tujuan", "Folder tidak dapat dibuka. Folder dibuat saat unduhan dimulai.")

    @Slot()
    def show_about(self) -> None:
        availability = []
        for name in ("engine", "yt_dlp", "requests"):
            try:
                present = importlib.util.find_spec(name) is not None
            except (ImportError, ValueError):
                present = False
            availability.append(f"{name}: {'tersedia' if present else 'tidak terdeteksi'}")
        box = QMessageBox(self)
        box.setWindowTitle("Tentang Brutal Downloader")
        box.setTextFormat(Qt.TextFormat.PlainText)
        box.setText("BRUTAL DOWNLOADER\n\n"
                    "Inspeksi dan unduhan mengakses jaringan. URL dapat dikirim ke resolver pihak ketiga "
                    "SnapSave / curl-x; kebijakan privasi dan batas layanan mereka berlaku. "
                    "Jangan masukkan URL privat atau URL bertoken sensitif.\n\n"
                    "Backend memakai sumber langsung atau yt-dlp, sesuai platform dan dependensi. "
                    "Deteksi modul bukan jaminan layanan tersedia.\n\n"
                    "Audio saja menyimpan format audio asli yang tersedia, bukan konversi MP3. "
                    "YouTube hanya memakai kualitas gabungan yang tersedia; tidak menjanjikan ffmpeg.\n\n"
                    "Dependensi lokal (pemeriksaan tanpa jaringan):\nPySide6: tersedia\n" + "\n".join(availability))
        box.exec()

    def closeEvent(self, event: QCloseEvent) -> None:
        if self._thread is not None:
            event.ignore()
            self._closing = True
            self.cancel_work()
            self.detail.setText("Menutup setelah worker berhenti. Pembatalan sedang diproses…")
            return
        event.accept()


def main() -> int:
    if "--check-backend" in sys.argv:
        # Offline check of modules loaded dynamically by the frozen application.
        try:
            importlib.import_module("engine")
            ytdlp = importlib.import_module("yt_dlp")
            with ytdlp.YoutubeDL({"quiet": True, "cachedir": False}) as downloader:
                downloader.get_info_extractor("Youtube")
            return 0
        except Exception:
            return 1
    smoke = "--smoke-test" in sys.argv
    app = QApplication([sys.argv[0]])
    app.setApplicationName("Brutal Downloader")
    app.setFont(QFont("Consolas", 10))
    app.setStyleSheet(STYLE)
    window = MainWindow()
    window.show()
    if smoke:
        # No inspection, clipboard read, directory creation, or backend import.
        QTimer.singleShot(120, window.close)
        QTimer.singleShot(1200, app.quit)
    return app.exec()


if __name__ == "__main__":
    raise SystemExit(main())
