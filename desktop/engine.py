"""Synchronous, GUI-independent downloader. Call from a worker thread.

Instagram resolution sends the public post URL to SnapSave and, if necessary,
curl-x. Callers must disclose these third-party services in their UI/docs.
Cancellation is cooperative; an in-flight request is bounded by socket timeouts.
Completed files survive later failures in a batch; unfinished staging is removed.
"""

from __future__ import annotations

import json
import re
import tempfile
import threading
from collections.abc import Callable
from contextlib import contextmanager
from dataclasses import dataclass
from html.parser import HTMLParser
from pathlib import Path
from typing import Any
from urllib.parse import unquote, urljoin, urlsplit

import requests
import yt_dlp


@dataclass(frozen=True)
class DownloadOptions:
    url: str
    destination: Path
    platform: str = 'AUTO-DETECT'
    quality: str = 'BEST'
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


_MAX_BODY = 8 * 1024 * 1024
_MAX_DECODED = 2 * 1024 * 1024
_TIMEOUT = (10, 20)
_UA = 'Mozilla/5.0'
_JS_STRING = r'''(?:"[^"\\]*(?:\\[\s\S][^"\\]*)*"|'[^'\\]*(?:\\[\s\S][^'\\]*)*')'''
_PACKED = re.compile(
    r'\}\s*\(\s*(' + _JS_STRING + r')\s*,\s*(-?\d+)\s*,\s*('
    + _JS_STRING + r')\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*(-?\d+)\s*\)\s*\)'
)
_ASSIGNMENT = re.compile(
    r'''document\s*\.\s*getElementById\s*\(\s*(['"])download-section\1\s*\)'''
    r'\s*\.\s*innerHTML\s*=\s*(' + _JS_STRING + r')'
)


def _check(cancel):
    if cancel.is_set():
        raise DownloadCancelled('Download cancelled')


def _url(value):
    if not isinstance(value, str) or not value or re.search(r'[\s\\\x00-\x1f\x7f]', value):
        raise DownloadError('Enter a valid HTTP or HTTPS URL')
    try:
        parsed = urlsplit(value)
        if (parsed.scheme.lower() not in ('http', 'https') or not parsed.hostname
                or parsed.username is not None or parsed.password is not None):
            raise ValueError()
        _ = parsed.port
    except ValueError as exc:
        raise DownloadError('Enter a valid HTTP or HTTPS URL without credentials') from exc
    return value  # Never unquote, normalize, or rebuild a signed media URL.


def _platform(options):
    _url(options.url)
    if options.quality not in ('BEST', '720', '480', '360'):
        raise DownloadError('Unsupported quality')
    if options.platform not in ('AUTO-DETECT', 'TIKTOK', 'INSTAGRAM', 'YOUTUBE', 'DIRECT LINK'):
        raise DownloadError('Unsupported platform')
    host = urlsplit(options.url).hostname.lower()
    detected = 'DIRECT LINK'
    for platform, domains in (
        ('INSTAGRAM', ('instagram.com',)),
        ('YOUTUBE', ('youtube.com', 'youtu.be', 'youtube-nocookie.com')),
        ('TIKTOK', ('tiktok.com',)),
    ):
        if any(host == domain or host.endswith('.' + domain) for domain in domains):
            detected = platform
            break
    if options.platform not in ('AUTO-DETECT', 'DIRECT LINK', detected):
        raise DownloadError('URL does not match the selected platform')
    return detected if options.platform == 'AUTO-DETECT' else options.platform


def _instagram_url(url):
    _url(url)
    parsed = urlsplit(url)
    host = parsed.hostname.lower()
    match = re.fullmatch(r'/(p|reel|reels|tv)/([A-Za-z0-9_-]+)/?', parsed.path)
    if not (host == 'instagram.com' or host.endswith('.instagram.com')) or not match:
        raise DownloadError('Use a public Instagram post or reel URL')
    kind = 'reel' if match[1] == 'reels' else match[1]
    return f'https://www.instagram.com/{kind}/{match[2]}/'


class _Session(requests.Session):
    def resolve_redirects(self, *args, **kwargs):
        # Session.send otherwise prepares a requoted _next URL and eagerly reads
        # redirect bodies even with allow_redirects=False. Follow them ourselves.
        yield from ()


@contextmanager
def _response(method, url, cancel, **kwargs):
    _check(cancel)
    _url(url)
    headers = {'User-Agent': _UA, **kwargs.pop('headers', {})}
    with _Session() as session:
        for _ in range(11):
            _check(cancel)
            request = requests.Request(method, url, headers=headers, **kwargs)
            prepared = session.prepare_request(request)
            # Preserve signed path/query bytes, including percent-escape case.
            prepared.url = url
            settings = session.merge_environment_settings(url, {}, True, None, None)
            with session.send(prepared, timeout=_TIMEOUT, allow_redirects=False, **settings) as response:
                _check(cancel)
                response.raise_for_status()
                _url(response.url)
                if response.status_code in (301, 302, 303, 307, 308):
                    location = response.headers.get('Location')
                    if not location:
                        raise DownloadError('Redirect has no Location URL')
                    url = _url(urljoin(url, location))
                    if response.status_code in (301, 302, 303) and method != 'HEAD':
                        method = 'GET'
                        kwargs = {}
                    continue
                yield response
                return
    raise DownloadError('Too many redirects')


def _body(response, cancel):
    chunks = []
    total = 0
    for chunk in response.iter_content(64 * 1024):
        _check(cancel)
        total += len(chunk)
        if total > _MAX_BODY:
            raise DownloadError('Resolver response exceeds the 8 MiB limit')
        chunks.append(chunk)
    _check(cancel)
    try:
        return b''.join(chunks).decode('utf-8-sig')
    except UnicodeDecodeError as exc:
        raise DownloadError('Invalid UTF-8 in resolver response') from exc


def _js_unescape(value):
    def replace(match: re.Match[str]) -> str:
        escape = match[1]
        if escape.startswith(('x', 'u')):
            size = 2 if escape[0] == 'x' else 4
            if len(escape) != size + 1 or not re.fullmatch(r'[0-9a-fA-F]+', escape[1:]):
                raise DownloadError('Invalid JavaScript hex escape')
            return chr(int(escape[1:], 16))
        return {'n': '\n', 'r': '\r', 't': '\t', 'b': '\b', 'f': '\f',
                'v': '\v', '0': '\0', '\n': '', '\r': '', '\r\n': ''}.get(escape, escape)
    if re.search(r'(?<!\\)(?:\\\\)*\\$', value):
        raise DownloadError('Truncated JavaScript escape')
    return re.sub(r'\\(x.{0,2}|u.{0,4}|\r\n|[\s\S])', replace, value)


def _unpack(body, cancel):
    if len(body.encode('utf-8')) > _MAX_BODY:
        raise DownloadError('Resolver response exceeds the 8 MiB limit')
    match = _PACKED.search(body)
    if not match:
        return body
    payload = _js_unescape(match[1][1:-1])
    alphabet = _js_unescape(match[3][1:-1])
    try:
        offset, base = int(match[4]), int(match[5])
    except ValueError as exc:
        raise DownloadError('Invalid SnapSave parameters') from exc
    if not (2 <= base <= 10 and base < len(alphabet) <= 64 and len(set(alphabet)) == len(alphabet)):
        raise DownloadError('Invalid SnapSave base/alphabet')
    if not payload:
        raise DownloadError('Empty SnapSave payload')
    result = bytearray()
    value = digits = 0
    delimiter = alphabet[base]
    for index, character in enumerate(payload):
        if index % 4096 == 0:
            _check(cancel)
        if character == delimiter:
            if not digits or not 0 <= value - offset <= 255:
                raise DownloadError('Invalid SnapSave character segment')
            result.append(value - offset)
            if len(result) > _MAX_DECODED:
                raise DownloadError('Decoded SnapSave payload exceeds size limit')
            value = digits = 0
        else:
            digit = alphabet.find(character)
            digits += 1
            if not 0 <= digit < base or digits > 32:
                raise DownloadError('Invalid SnapSave digit')
            value = value * base + digit
            if value > 2**31 + 254:
                raise DownloadError('SnapSave character segment overflow')
    if digits:
        raise DownloadError('Truncated SnapSave payload')
    try:
        return result.decode('utf-8')
    except UnicodeDecodeError as exc:
        raise DownloadError('Invalid UTF-8 in SnapSave payload') from exc


class _Anchors(HTMLParser):
    def __init__(self):
        super().__init__(convert_charrefs=True)
        self.links = []
        self.anchor = None
        self.hidden = 0

    def handle_starttag(self, tag, attrs):
        if tag in ('script', 'style'):
            self.hidden += 1
        if not self.hidden and tag == 'a':
            self.anchor = (dict(reversed(attrs)), [])

    def handle_data(self, data):
        if self.anchor and not self.hidden:
            self.anchor[1].append(data)

    def handle_endtag(self, tag):
        if tag in ('script', 'style'):
            self.hidden = max(0, self.hidden - 1)
        if tag == 'a' and self.anchor and not self.hidden:
            attrs, text = self.anchor
            self.anchor = None
            def kind(label):
                return {'download photo': 'photo', 'download video': 'video'}.get(
                    ' '.join((label or '').lower().split()))
            title_kind, text_kind = kind(attrs.get('title')), kind(' '.join(text))
            if title_kind and text_kind and title_kind != text_kind:
                return
            media_kind = title_kind or text_kind
            url = attrs.get('href')
            if media_kind and url:
                try:
                    _url(url)
                except DownloadError:
                    return
                self.links.append((url, media_kind))


def _snapsave_parse(body, cancel):
    decoded = _unpack(body, cancel)
    assignments = list(_ASSIGNMENT.finditer(decoded))
    fragments = [_js_unescape(m[2][1:-1]) for m in assignments] if assignments else [decoded]
    parser = _Anchors()
    for fragment in fragments:
        _check(cancel)
        parser.feed(fragment)
    if not parser.links and not assignments and ('\\"' in decoded or "\\'" in decoded):
        parser = _Anchors()
        parser.feed(_js_unescape(decoded))
    seen = set()
    result = []
    for url, kind in parser.links:
        if url not in seen:
            seen.add(url)
            result.append(MediaItem(url, f'Instagram {len(result) + 1}', kind, 'direct', len(result) + 1))
    if not result:
        raise DownloadError('SnapSave returned no valid Download Photo/Video links')
    return result


def _curlx_parse(body, expected):
    try:
        data = json.loads(body)
        if not isinstance(data, dict) or data.get('platform') != 'instagram':
            raise ValueError('wrong platform')
        if _instagram_url(data.get('tweetUrl')) != expected:
            raise ValueError('post mismatch')
        media = data.get('media')
        if not isinstance(media, list):
            raise TypeError('missing media')
        result, seen = [], set()
        for entry in media:
            if not isinstance(entry, dict):
                raise TypeError('invalid media entry')
            kind = {'video': 'video', 'photo': 'photo', 'image': 'photo'}.get(str(entry.get('type')))
            variants = entry.get('variants', [])
            if not kind or not isinstance(variants, list):
                raise ValueError('unsupported media entry')
            candidates = []
            for variant in variants:
                if not isinstance(variant, dict):
                    continue
                content_type = variant.get('contentType', '').split(';')[0].lower()
                if not ((kind == 'video' and content_type == 'video/mp4')
                        or (kind == 'photo' and content_type.startswith('image/'))):
                    continue
                url = _url(variant.get('url'))
                if urlsplit(url).scheme.lower() != 'https':
                    continue
                candidates.append(url)
            if not candidates:
                # Do not silently return an incomplete carousel.
                raise ValueError('media entry has no valid variant')
            url = candidates[0]
            if url not in seen:
                seen.add(url)
                result.append(MediaItem(url, f'Instagram {len(result) + 1}', kind, 'direct', len(result) + 1))
        if not result:
            raise ValueError('no media')
        return result
    except (ValueError, TypeError, AttributeError, DownloadError) as exc:
        raise DownloadError('curl-x returned invalid or mismatched Instagram media') from exc


def _instagram(options, cancel, log):
    clean = _instagram_url(options.url)
    log('Instagram: sending the public post URL to third-party resolver SnapSave (snapsave.app).')
    try:
        with _response('POST', 'https://snapsave.app/action.php', cancel,
                       data={'url': clean}, headers={'Referer': 'https://snapsave.app/',
                                                   'Origin': 'https://snapsave.app'}) as response:
            return _snapsave_parse(_body(response, cancel), cancel)
    except DownloadCancelled:
        raise
    except (requests.RequestException, DownloadError):
        _check(cancel)
        log('SnapSave returned no usable media; sending the public post URL to third-party curl-x.com.')
    try:
        with _response('POST', 'https://www.curl-x.com/api/extract', cancel,
                       json={'url': clean}) as response:
            return _curlx_parse(_body(response, cancel), clean)
    except DownloadCancelled:
        raise
    except (requests.RequestException, DownloadError):
        _check(cancel)
        log('curl-x returned no usable media; trying yt-dlp for Instagram.')
    return _inspect_ytdlp(options, cancel, log)


def _signature(data):
    if data.startswith(b'\xff\xd8\xff'):
        return 'photo', 'jpg'
    if data.startswith(b'\x89PNG\r\n\x1a\n'):
        return 'photo', 'png'
    if data.startswith((b'GIF87a', b'GIF89a')):
        return 'photo', 'gif'
    if len(data) >= 12 and data[:4] == b'RIFF':
        if data[8:12] == b'WEBP':
            return 'photo', 'webp'
        if data[8:12] == b'WAVE':
            return 'audio', 'wav'
        if data[8:12] == b'AVI ':
            return 'video', 'avi'
    if len(data) >= 12 and data[4:8] == b'ftyp':
        brands = data[8:64]
        if any(brand in brands for brand in (b'avif', b'avis')):
            return 'photo', 'avif'
        if any(brand in brands for brand in (b'heic', b'heix', b'heif', b'mif1')):
            return 'photo', 'heic'
        if data[8:12] in (b'M4A ', b'M4B ', b'F4A '):
            return 'audio', 'm4a'
        return 'video', 'mp4'
    if data.startswith(b'fLaC'):
        return 'audio', 'flac'
    if data.startswith(b'OggS'):
        return ('video', 'ogv') if b'theora' in data else ('audio', 'ogg')
    if data.startswith(b'ID3'):
        return 'audio', 'mp3'
    if len(data) >= 4 and data[0] == 255 and data[1] & 0xE0 == 0xE0:
        if data[1] & 0xF6 == 0xF0:
            return 'audio', 'aac'
        if data[1] & 0x06 and data[2] & 0xF0 not in (0, 0xF0) and data[2] & 0x0C != 0x0C:
            return 'audio', 'mp3'
    if data.startswith(b'\x1aE\xdf\xa3'):
        return 'video', 'webm'
    raise DownloadError('Response is not recognized media (possibly HTML, JSON, or an expired link)')


def _validate_media(data, content_type='', expected=None, extension=None):
    mime = content_type.split(';')[0].strip().lower()
    if mime and not (mime.startswith(('image/', 'video/', 'audio/'))
                     or mime in ('application/octet-stream', 'binary/octet-stream',
                                 'application/ogg', 'application/mp4')):
        raise DownloadError('Server returned a non-media Content-Type')
    kind, ext = _signature(data)
    # MP4/WebM are containers: native m4a/WebM audio may have video-like magic.
    if kind == 'video' and expected == 'audio' and extension in ('m4a', 'webm', 'mp4'):
        kind, ext = 'audio', extension
    elif kind == 'video' and mime.startswith('audio/'):
        kind = 'audio'
        if ext == 'mp4':
            ext = 'm4a'
    if expected and kind != expected:
        raise DownloadError(f'Expected {expected}, but received {kind} media')
    return kind, ext


def _prefix(response, cancel):
    data = bytearray()
    for chunk in response.iter_content(4096):
        _check(cancel)
        data.extend(chunk)
        if len(data) >= 4096:
            break
    _check(cancel)
    return bytes(data)


def _direct_inspect(options, cancel, log):
    with _response('GET', options.url, cancel) as response:
        kind, _ = _validate_media(_prefix(response, cancel), response.headers.get('Content-Type', ''))
    if options.audio_only and kind != 'audio':
        raise DownloadError('Direct links must already contain audio-only media; FFmpeg is not used')
    title = unquote(urlsplit(options.url).path.rsplit('/', 1)[-1]) or 'download'
    return [MediaItem(options.url, Path(title).stem or 'download', kind, 'direct')]


def _number(value):
    return value if isinstance(value, (int, float)) and value >= 0 else 0


def _select_format(formats, options, log):
    usable = []
    for fmt in formats:
        if not isinstance(fmt, dict) or not fmt.get('url') or not fmt.get('format_id'):
            continue
        protocol = fmt.get('protocol') or urlsplit(fmt['url']).scheme
        if protocol not in ('http', 'https') or fmt.get('has_drm'):
            continue
        try:
            _url(fmt['url'])
        except DownloadError:
            continue
        video = fmt.get('vcodec') not in (None, 'none')
        audio = fmt.get('acodec') not in (None, 'none')
        if options.audio_only:
            if audio and fmt.get('vcodec') == 'none' and re.fullmatch(r'[a-zA-Z0-9]{1,10}', fmt.get('ext') or ''):
                usable.append(fmt)
        elif video and audio and fmt.get('ext') == 'mp4':
            usable.append(fmt)
    if not usable:
        message = 'native audio-only HTTP stream' if options.audio_only else 'progressive MP4 stream containing both video and audio'
        raise DownloadError(f'No {message} is available; separate streams/HLS require FFmpeg and are not used')
    if options.audio_only:
        return max(usable, key=lambda f: (_number(f.get('abr') or f.get('tbr')), _number(f.get('asr'))))
    known = [f for f in usable if _number(f.get('height'))]
    if options.quality == 'BEST':
        selected = max(usable, key=lambda f: (_number(f.get('height')), _number(f.get('tbr'))))
        actual = f"{selected['height']}p" if selected.get('height') else 'unknown height'
        log(f'BEST: using best available progressive MP4 ({actual}, audio included), not separate streams.')
        return selected
    target = int(options.quality)
    below = [f for f in known if f['height'] <= target]
    if below:
        selected = max(below, key=lambda f: (f['height'], _number(f.get('tbr'))))
    elif known:
        selected = min(known, key=lambda f: (f['height'], -_number(f.get('tbr'))))
    else:
        selected = max(usable, key=lambda f: _number(f.get('tbr')))
    if selected.get('height') != target:
        actual = f"{selected['height']}p" if selected.get('height') else 'unknown height'
        log(f'Requested {target}p unavailable as progressive MP4; using {actual} without FFmpeg.')
    return selected


class _YDLLogger:
    def __init__(self, cancel, log):
        self.cancel, self.log = cancel, log

    def debug(self, message):
        _check(self.cancel)

    def warning(self, message):
        _check(self.cancel)
        self.log('yt-dlp: ' + str(message))

    def error(self, message):
        _check(self.cancel)
        self.log('yt-dlp: ' + str(message))


def _ydl_options(options, cancel, log, hook=None, stage=None) -> Any:
    def selector(context):
        _check(cancel)
        yield _select_format(context.get('formats', []), options, log)
    params = {
        'noplaylist': True, 'quiet': True, 'no_warnings': False,
        'logger': _YDLLogger(cancel, log), 'format': selector,
        'socket_timeout': 15, 'retries': 1, 'extractor_retries': 1,
        'fragment_retries': 0, 'continuedl': False, 'overwrites': False,
        'fixup': 'never', 'postprocessors': [], 'cachedir': False,
        'progress_hooks': [hook] if hook else [lambda _: _check(cancel)],
        'check_formats': False,
    }
    if stage is not None:
        params['outtmpl'] = str(stage / 'media.%(ext)s')
    return params


def _entries(info):
    if not isinstance(info, dict):
        raise DownloadError('yt-dlp returned no media')
    if info.get('entries') is not None:
        result = []
        for entry in info['entries']:
            result.extend(_entries(entry))
        if not result:
            raise DownloadError('yt-dlp returned an empty playlist/carousel')
        return result
    return [info]


def _inspect_ytdlp(options, cancel, log):
    with yt_dlp.YoutubeDL(_ydl_options(options, cancel, log)) as ydl:
        info = ydl.extract_info(options.url, download=False)
        _check(cancel)
        result = []
        for index, entry in enumerate(_entries(info), 1):
            _select_format(entry.get('formats') or [entry], options, log)
            result.append(MediaItem(options.url, str(entry.get('title') or f'download {index}'),
                                    'audio' if options.audio_only else 'video', 'ytdlp', index))
        return result


def inspect_media(options: DownloadOptions, cancel: threading.Event,
                  log: Callable[[str], None]) -> list[MediaItem]:
    _check(cancel)
    platform = _platform(options)
    try:
        if platform == 'DIRECT LINK':
            return _direct_inspect(options, cancel, log)
        if platform == 'INSTAGRAM' and not options.audio_only:
            return _instagram(options, cancel, log)
        return _inspect_ytdlp(options, cancel, log)
    except DownloadError:
        raise
    except Exception as exc:
        _check(cancel)
        raise DownloadError(f'Cannot inspect media: {exc}') from exc


def _safe_name(title):
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f\x7f]', '_', str(title))
    name = name.strip(' .')[:120].rstrip(' .') or 'download'
    if re.match(r'^(CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³])(?:\.|$)', name, re.IGNORECASE):
        name = '_' + name
    return name


def _publish(stage, destination, title, extension, cancel):
    name = _safe_name(title)
    # Exclusive reservation plus copy is portable to Windows and never replaces
    # an existing user file. Remove the reservation if copying/cancellation fails.
    for index in range(1, 10001):
        _check(cancel)
        suffix = '' if index == 1 else f' ({index})'
        target = destination / f'{name}{suffix}.{extension}'
        try:
            output = target.open('xb')
        except FileExistsError:
            continue
        try:
            with output, stage.open('rb') as source:
                while chunk := source.read(1024 * 1024):
                    _check(cancel)
                    output.write(chunk)
            _check(cancel)
        except BaseException:
            target.unlink(missing_ok=True)
            raise
        return target
    raise DownloadError('Too many files with the same name in the destination')


def _download_direct(options, item, stage, cancel, report, log):
    if options.audio_only and item.kind != 'audio':
        raise DownloadError('Cannot extract audio from a direct media link without FFmpeg')
    if options.quality != 'BEST' and item.kind == 'video':
        log('Direct media: saving supplied stream; resolution cannot be changed without transcoding.')
    path = stage / 'media.part'
    with _response('GET', item.url, cancel) as response:
        if response.status_code != 200:
            raise DownloadError(f'Full media download requires HTTP 200; received HTTP {response.status_code}')
        mime = response.headers.get('Content-Type', '')
        try:
            total = int(response.headers.get('Content-Length', 0))
        except ValueError:
            total = 0
        downloaded = 0
        prefix = bytearray()
        with path.open('xb') as output:
            for chunk in response.iter_content(64 * 1024):
                _check(cancel)
                if not chunk:
                    continue
                if len(prefix) < 4096:
                    prefix.extend(chunk[:4096 - len(prefix)])
                    # Accumulate the same bounded prefix used by inspection so
                    # split headers are safe, but reject errors before the tail.
                    if len(prefix) == 4096:
                        _validate_media(bytes(prefix), mime, item.kind)
                output.write(chunk)
                downloaded += len(chunk)
                report(min(99, int(downloaded * 100 / total)) if total > 0 else 0)
        _check(cancel)
        # Compressed Content-Length describes wire bytes, not iter_content output.
        if total > 0 and not response.headers.get('Content-Encoding') and downloaded != total:
            raise DownloadError('Incomplete media response (Content-Length mismatch)')
        _, extension = _validate_media(bytes(prefix), mime, item.kind)
    return path, extension


def _download_ytdlp(options, item, stage, cancel, report, log):
    def hook(status):
        _check(cancel)
        total = status.get('total_bytes') or status.get('total_bytes_estimate') or 0
        done = status.get('downloaded_bytes') or 0
        report(min(99, int(done * 100 / total)) if total else 0)
    with yt_dlp.YoutubeDL(_ydl_options(options, cancel, log, hook, stage)) as ydl:
        info = ydl.extract_info(item.url, download=False)
        _check(cancel)
        entries = _entries(info)
        if not 1 <= item.index <= len(entries):
            raise DownloadError('Media carousel changed since inspection; inspect again')
        entry = entries[item.index - 1]
        selected = _select_format(entry.get('formats') or [entry], options, log)
        # process_info receives exactly one self-contained stream, never a merge.
        download_info: Any = dict(entry)
        download_info.pop('requested_formats', None)
        download_info.pop('requested_downloads', None)
        download_info.update(selected)
        ydl.process_info(download_info)
        _check(cancel)
        path = Path(ydl.prepare_filename(download_info))
        if path.parent.resolve() != stage.resolve() or not path.is_file():
            raise DownloadError('yt-dlp did not produce the expected media file')
        with path.open('rb') as source:
            _validate_media(source.read(4096), expected=item.kind, extension=selected['ext'])
        return path, selected['ext']


def download_media(options: DownloadOptions, items: list[MediaItem], cancel: threading.Event,
                   progress: Callable[[int, str], None], log: Callable[[str], None]) -> list[Path]:
    _check(cancel)
    _platform(options)
    if not items:
        raise DownloadError('No media selected')
    for item in items:
        _url(item.url)
        if item.source not in ('direct', 'ytdlp') or item.kind not in ('photo', 'video', 'audio') or item.index < 1:
            raise DownloadError('Invalid media item')
    destination = Path(options.destination)
    results = []
    try:
        destination.mkdir(parents=True, exist_ok=True)
        progress(0, 'Starting download')
        for index, item in enumerate(items):
            _check(cancel)
            def report(percent, index=index, item=item):
                _check(cancel)
                progress(min(99, int((index * 100 + percent) / len(items))),
                         f'{index + 1}/{len(items)}: {item.title}')
            with tempfile.TemporaryDirectory(prefix='.download-', suffix='.part', dir=destination) as folder:
                stage = Path(folder)
                handler = _download_direct if item.source == 'direct' else _download_ytdlp
                path, extension = handler(options, item, stage, cancel, report, log)
                _check(cancel)
                final = _publish(path, destination, item.title, extension, cancel)
            results.append(final)
            log(f'Saved {final.name}')
            progress(int((index + 1) * 100 / len(items)), f'Saved {final.name}')
        return results
    except DownloadError:
        raise
    except Exception as exc:
        _check(cancel)
        raise DownloadError(f'Download failed: {exc}') from exc
