# Third-party notices

Brutal Downloader Desktop bundles independently licensed components. This document does not change their licenses or grant rights to downloaded content.

- **PySide6 / Shiboken / Qt**: LGPL-3.0, GPL, and/or commercial terms, depending on the component. This app uses Qt Core, Gui and Widgets through dynamically loaded PySide6 libraries. Sources, licensing details, and corresponding versions: https://code.qt.io/pyside/pyside-setup.git/ and https://code.qt.io/qt/ . Qt license information: https://www.qt.io/licensing/open-source-lgpl-obligations . The source application's requirements allow running it with separately obtained or modified compatible libraries. The one-file package unpacks the dynamic libraries into a temporary directory when launched. Do not restrict reverse engineering for debugging modifications to LGPL-covered components. Use the source distribution if you need to replace Qt libraries.
- **yt-dlp**: Unlicense (source), with independently licensed dependencies if present. https://github.com/yt-dlp/yt-dlp . This application bundles the Python package, not the upstream standalone yt-dlp executable. Review the license files included in this ZIP.
- **Requests**: Apache-2.0. https://github.com/psf/requests
- **urllib3**: MIT. https://github.com/urllib3/urllib3
- **certifi**: MPL-2.0 (CA certificate bundle). https://github.com/certifi/python-certifi
- **charset-normalizer**: MIT. https://github.com/jawah/charset_normalizer
- **idna**: BSD-3-Clause. https://github.com/kjd/idna
- **Python**: PSF license and notices. https://www.python.org/psf/license/
- **PyInstaller bootloader**: GPL with the PyInstaller bootloader exception permitting distribution of bundled applications. https://pyinstaller.org/en/stable/license.html

The portable ZIP includes available installed dependency license/notice files under `licenses/`, including the LGPLv3/GPLv3 texts, Python license, and PyInstaller license/bootloader exception. The `source/` folder includes the desktop application code and build instructions so you can modify and rebuild it with compatible replacement libraries. You are permitted to modify and rebuild these desktop sources for the purpose of recombining/relinking with modified LGPL-covered components, and to reverse engineer this application for debugging those modifications. Obtain exact package versions from `requirements.txt` and `requirements-build.txt` in this repository. No FFmpeg or JavaScript runtime is bundled.

SnapSave and curl-x are external services, not bundled software. Submitted public Instagram URLs may be shared with these operators. Their availability, privacy policies, rate limits, and terms apply. This application is not affiliated with Instagram, TikTok, YouTube, SnapSave, or curl-x.
