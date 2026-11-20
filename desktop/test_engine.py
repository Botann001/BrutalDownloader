"""Offline backend tests: python -m unittest discover -s desktop -v."""

import io
import json
import tempfile
import threading
import unittest
from dataclasses import FrozenInstanceError, fields
from pathlib import Path
from typing import Any
from unittest.mock import Mock, patch

import engine
import requests
import yt_dlp

JPEG = b'\xff\xd8\xff\xe0' + b'photo data' * 100
PNG = b'\x89PNG\r\n\x1a\n' + b'png data' * 100
MP4 = b'\x00\x00\x00\x18ftypisom\x00\x00\x00\x00isommp42' + b'video data' * 100
M4A = b'\x00\x00\x00\x18ftypM4A \x00\x00\x00\x00isommp42' + b'audio data' * 100
SIGNED = 'https://cdn.example/media%2fraw.mp4?sig=a%2fb%2Bc+z&x=%252F&x=2'
POST = 'https://www.instagram.com/p/ABC_123/'


class Response:
    def __init__(self, body=b'', mime='application/octet-stream', url=SIGNED,
                 chunks=None, headers=None, on_chunk=None, error=None):
        self.body = body
        self.status_code = 200
        self.url = url
        self.headers = {'Content-Type': mime, **(headers or {})}
        self.chunks = chunks
        self.on_chunk = on_chunk
        self.error = error
        self.closed = False

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.closed = True

    def raise_for_status(self):
        if self.error:
            raise self.error

    def iter_content(self, size):
        chunks = self.chunks if self.chunks is not None else [self.body[i:i + size] for i in range(0, len(self.body), size)]
        for chunk in chunks:
            if self.on_chunk:
                self.on_chunk()
            if isinstance(chunk, Exception):
                raise chunk
            yield chunk


def fmt(identifier='combined', height: int | None = 720, *, ext='mp4', video='h264', audio: str | None = 'aac',
        protocol='https', bitrate=1000):
    return {'format_id': identifier, 'url': 'https://cdn.example/' + identifier,
            'height': height, 'ext': ext, 'vcodec': video, 'acodec': audio,
            'protocol': protocol, 'tbr': bitrate}


def packed(fragment, offset=7):
    # Same decimal-substitution algorithm as Android, including UTF-8 bytes.
    alphabet = 'abcdefghijk'
    payload = ''.join(''.join(alphabet[int(d)] for d in str(byte + offset)) + alphabet[10]
                      for byte in fragment.encode('utf-8'))
    return f'}}("{payload}",0,"{alphabet}",{offset},10,0))'


class EngineTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.destination = Path(self.temp.name)
        self.cancel = threading.Event()
        self.log = Mock()
        self.progress = Mock()
        # Every test is offline even if its expected mock is accidentally missed.
        self.send = self.enterContext(patch('requests.Session.send', side_effect=AssertionError('Unexpected network')))
        self.ydl = self.enterContext(patch('engine.yt_dlp.YoutubeDL', side_effect=AssertionError('Unexpected yt-dlp')))

    def options(self, url=SIGNED, **kwargs):
        return engine.DownloadOptions(url, self.destination, **kwargs)

    def item(self, kind='video', title='Title', source='direct', url=SIGNED, index=1):
        return engine.MediaItem(url, title, kind, source, index)

    def inspect(self, options=None):
        return engine.inspect_media(options or self.options(), self.cancel, self.log)

    def download(self, items=None, options=None):
        return engine.download_media(options or self.options(), items or [self.item()],
                                     self.cancel, self.progress, self.log)

    def assert_empty(self):
        self.assertEqual(list(self.destination.iterdir()), [])

    def test_contract_and_immutable_defaults(self):
        options = self.options()
        self.assertEqual([f.name for f in fields(options)],
                         ['url', 'destination', 'platform', 'quality', 'audio_only'])
        self.assertEqual((options.platform, options.quality, options.audio_only), ('AUTO-DETECT', 'BEST', False))
        self.assertEqual([f.name for f in fields(engine.MediaItem)], ['url', 'title', 'kind', 'source', 'index'])
        dynamic_options: Any = options
        with self.assertRaises(FrozenInstanceError):
            dynamic_options.quality = '720'
        self.assertTrue(issubclass(engine.DownloadCancelled, engine.DownloadError))

    def test_invalid_urls_never_make_requests(self):
        for url in ('', 'file:///a.mp4', 'ftp://example.com/a', '//example.com/a',
                    'https://', 'https://user:pass@example.com/a', 'https://example.com:bad/a',
                    'https://example.com/a\nb', 'https://example.com\\@evil/a', 'not a URL'):
            with self.subTest(url=url), self.assertRaises(engine.DownloadError):
                self.inspect(self.options(url))
        self.send.assert_not_called()

    def test_platform_and_quality_validation(self):
        for kwargs in ({'platform': 'OTHER'}, {'quality': '1080'}, {'platform': 'YOUTUBE'}):
            with self.subTest(kwargs=kwargs), self.assertRaises(engine.DownloadError):
                self.inspect(self.options(**kwargs))
        self.send.assert_not_called()

    def test_exact_signed_query_stream_timeout_and_response_closed(self):
        response = Response(MP4, 'video/mp4')
        self.send.side_effect = None
        self.send.return_value = response
        items = self.inspect()
        self.assertEqual(items[0].url, SIGNED)
        request = self.send.call_args.args[0]
        self.assertEqual(request.url, SIGNED)
        self.assertTrue(self.send.call_args.kwargs['stream'])
        self.assertEqual(self.send.call_args.kwargs['timeout'], (10, 20))
        self.assertTrue(response.closed)
        self.assertNotIn('Cookie', request.headers)

    def test_direct_link_override_bypasses_instagram(self):
        self.send.side_effect = None
        self.send.return_value = Response(JPEG, 'image/jpeg')
        result = self.inspect(self.options(POST, platform='DIRECT LINK'))
        self.assertEqual(result[0].kind, 'photo')
        self.assertEqual(self.send.call_args.args[0].method, 'GET')
        self.ydl.assert_not_called()

    def test_direct_signatures_not_filename_or_mime(self):
        for data, mime in ((b'<html>login</html>', 'video/mp4'),
                           (b'{"error":"expired"}', 'image/jpeg'),
                           (MP4, 'text/html'), (b'', 'video/mp4')):
            self.send.side_effect = None
            self.send.return_value = Response(data, mime)
            with self.subTest(data=data[:12], mime=mime), self.assertRaises(engine.DownloadError):
                self.inspect()

    def test_photo_audio_and_container_signatures(self):
        cases = [(JPEG, ('photo', 'jpg')), (PNG, ('photo', 'png')),
                 (b'GIF89a123', ('photo', 'gif')),
                 (b'RIFF1234WEBP123', ('photo', 'webp')),
                 (b'RIFF1234WAVE123', ('audio', 'wav')),
                 (M4A, ('audio', 'm4a')), (MP4, ('video', 'mp4')),
                 (b'fLaC123', ('audio', 'flac')), (b'ID3abc', ('audio', 'mp3')),
                 (b'OggS123OpusHead', ('audio', 'ogg')),
                 (b'\xff\xfb\x90\x00', ('audio', 'mp3')),
                 (b'\xff\xf1\x50\x80', ('audio', 'aac'))]
        for data, expected in cases:
            with self.subTest(expected=expected):
                self.assertEqual(engine._signature(data), expected)

    def test_direct_inspection_reads_only_prefix(self):
        def chunks():
            yield MP4 + b'0' * 4096
            raise AssertionError('Inspection downloaded whole file')
        self.send.side_effect = None
        self.send.return_value = Response(chunks=chunks())
        self.assertEqual(self.inspect()[0].kind, 'video')

    def test_audio_only_direct_rejects_video_without_transcoding(self):
        self.send.side_effect = None
        self.send.return_value = Response(MP4)
        with self.assertRaisesRegex(engine.DownloadError, 'FFmpeg'):
            self.inspect(self.options(audio_only=True))
        with self.assertRaisesRegex(engine.DownloadError, 'FFmpeg'):
            self.download(options=self.options(audio_only=True))
        self.assert_empty()

    def test_pre_cancelled_inspect_and_download(self):
        self.cancel.set()
        with self.assertRaises(engine.DownloadCancelled):
            self.inspect()
        with self.assertRaises(engine.DownloadCancelled):
            self.download()
        self.send.assert_not_called()
        self.ydl.assert_not_called()
        self.assert_empty()

    def test_cancel_during_inspection_closes_response(self):
        response = Response(MP4, on_chunk=self.cancel.set)
        self.send.side_effect = None
        self.send.return_value = response
        with self.assertRaises(engine.DownloadCancelled):
            self.inspect()
        self.assertTrue(response.closed)

    def test_download_unique_windows_names_and_original_content(self):
        self.send.side_effect = None
        self.send.return_value = Response(MP4, 'video/mp4', headers={'Content-Length': str(len(MP4))})
        items = [self.item(title='CON'), self.item(title='CON'), self.item(title='../bad:<name>?* .')]
        result = self.download(items)
        self.assertEqual([p.name for p in result], ['_CON.mp4', '_CON (2).mp4', '_bad__name___.mp4'])
        for path in result:
            self.assertEqual(path.read_bytes(), MP4)
        self.assertFalse(any(p.name.endswith('.part') for p in self.destination.iterdir()))
        self.assertEqual(self.progress.call_args.args[0], 100)
        values = [call.args[0] for call in self.progress.call_args_list]
        self.assertEqual(values, sorted(values))

    def test_existing_file_is_never_overwritten(self):
        existing = self.destination / 'Title.mp4'
        existing.write_bytes(b'user file')
        self.send.side_effect = None
        self.send.return_value = Response(MP4)
        result = self.download()
        self.assertEqual(existing.read_bytes(), b'user file')
        self.assertEqual(result[0].name, 'Title (2).mp4')

    def test_signature_determines_extension_not_url(self):
        self.send.side_effect = None
        self.send.return_value = Response(PNG, 'application/octet-stream')
        result = self.download([self.item(kind='photo')])
        self.assertEqual(result[0].suffix, '.png')

    def test_invalid_media_wrong_kind_and_length_cleanup(self):
        for response in (Response(b'HTML', 'video/mp4'), Response(JPEG, 'image/jpeg'),
                         Response(MP4, headers={'Content-Length': str(len(MP4) + 1)})):
            self.send.side_effect = None
            self.send.return_value = response
            with self.subTest(response=response), self.assertRaises(engine.DownloadError):
                self.download()
            self.assert_empty()

    def test_full_direct_download_requires_http_200(self):
        for status in (206, 204, 201):
            response = Response(MP4, 'video/mp4', headers={
                'Content-Length': str(len(MP4)),
                'Content-Range': f'bytes 0-{len(MP4) - 1}/{len(MP4) * 2}',
            })
            response.status_code = status
            self.send.side_effect = None
            self.send.return_value = response
            with self.subTest(status=status):
                with self.assertRaisesRegex(engine.DownloadError, 'HTTP 200'):
                    self.download()
                self.assertTrue(response.closed)
                self.assert_empty()

    def test_direct_download_rejects_nonmedia_before_reading_body_tail(self):
        for mime in ('video/mp4', 'text/html', 'application/octet-stream'):
            tail_read = []
            def chunks(tail_read=tail_read):
                yield b'<ht'
                yield b'ml>' + b'error ' * 1000
                tail_read.append(True)
                yield b'remaining error page' * 1000
            response = Response(mime=mime, chunks=chunks())
            self.send.side_effect = None
            self.send.return_value = response
            with self.subTest(mime=mime):
                with self.assertRaises(engine.DownloadError):
                    self.download()
                self.assertFalse(tail_read, 'Invalid prefix must stop further response consumption')
                self.assertTrue(response.closed)
                self.assert_empty()

    def test_direct_download_accepts_signatures_split_across_chunks(self):
        for payload, kind, mime in ((MP4, 'video', 'video/mp4'),
                                    (PNG, 'photo', 'image/png'),
                                    (M4A, 'audio', 'audio/mp4')):
            payload += b'payload' * 1000
            chunks = [payload[:1], b'', payload[1:3], payload[3:8], payload[8:12],
                      payload[12:4095], payload[4095:4096], payload[4096:]]
            self.send.side_effect = None
            self.send.return_value = Response(mime=mime, chunks=chunks,
                                              headers={'Content-Length': str(len(payload))})
            with self.subTest(kind=kind):
                result = self.download([self.item(kind=kind)])
                self.assertEqual(result[0].read_bytes(), payload)

    def test_direct_quality_log_does_not_claim_original_source_quality(self):
        self.send.side_effect = None
        self.send.return_value = Response(MP4, 'video/mp4')
        self.download(options=self.options(quality='720'))
        self.log.assert_any_call(
            'Direct media: saving supplied stream; resolution cannot be changed without transcoding.')
        self.assertFalse(any('original quality' in call.args[0] for call in self.log.call_args_list))

    def test_network_failure_and_cancel_remove_partial_files(self):
        for chunks, on_chunk, error in (
            ([MP4, requests.ConnectionError('dropped')], None, engine.DownloadError),
            ([MP4], self.cancel.set, engine.DownloadCancelled),
        ):
            self.cancel.clear()
            self.send.side_effect = None
            self.send.return_value = Response(chunks=chunks, on_chunk=on_chunk)
            with self.subTest(error=error), self.assertRaises(error):
                self.download()
            self.assert_empty()

    def test_http_error_is_download_error_and_closed(self):
        response = Response(error=requests.HTTPError('403 Forbidden'))
        self.send.side_effect = None
        self.send.return_value = response
        with self.assertRaises(engine.DownloadError):
            self.download()
        self.assertTrue(response.closed)
        self.assert_empty()

    def test_unknown_length_progress_is_bounded(self):
        self.send.side_effect = None
        self.send.return_value = Response(chunks=[MP4, b'more'])
        self.download()
        self.assertTrue(all(0 <= call.args[0] <= 100 for call in self.progress.call_args_list))
        self.assertEqual(self.progress.call_args.args[0], 100)

    def test_batch_failure_keeps_completed_file_only(self):
        self.send.side_effect = [Response(JPEG, 'image/jpeg'), Response(b'bad')]
        with self.assertRaises(engine.DownloadError):
            self.download([self.item(kind='photo', title='First'), self.item(title='Second')])
        self.assertEqual([p.name for p in self.destination.iterdir()], ['First.jpg'])
        self.assertNotEqual(self.progress.call_args.args[0], 100)

    def test_cancel_during_publish_removes_reserved_file(self):
        stage = self.destination / 'input.part'
        stage.write_bytes(MP4)
        original_check = engine._check
        calls = 0
        def check(cancel):
            nonlocal calls
            calls += 1
            if calls == 2:
                cancel.set()
            original_check(cancel)
        with patch('engine._check', side_effect=check), self.assertRaises(engine.DownloadCancelled):
            engine._publish(stage, self.destination, 'Title', 'mp4', self.cancel)
        self.assertEqual(list(self.destination.iterdir()), [stage])

    def test_photo_carousel_packed_utf8_preserves_signed_urls(self):
        url = 'https://cdn.example/photo%2f1.jpg?sig=a+b%2Fc&amp;x=2'
        fragment = ("document.getElementById('download-section').innerHTML = "
                    + json.dumps(f'<a href="{url}" title="Download Photo">Download Photo</a>'
                                 '<a href="https://cdn.example/2.jpg">Download Photo</a>'
                                 '<a href="https://cdn.example/2.jpg">Download Photo</a> café') + ';')
        result = engine._snapsave_parse(packed(fragment), self.cancel)
        self.assertEqual(len(result), 2)
        self.assertEqual([i.kind for i in result], ['photo', 'photo'])
        self.assertEqual([i.index for i in result], [1, 2])
        self.assertEqual(result[0].url, url.replace('&amp;', '&'))

    def test_snapsave_rejects_broken_placeholder_conflict_and_thumbnails(self):
        invalid = ("<a href='&dl=1' title='Download Photo'>Download Video</a>"
                   "<img src='https://cdn.example/thumb.jpg'>"
                   "<a href='https://cdn.example/thumb.jpg'>Thumbnail</a>"
                   "<a href='https://cdn.example/wrong.jpg' title='Download Photo'>Download Video</a>"
                   "<script><a href='https://cdn.example/script.jpg'>Download Photo</a></script>"
                   "<!--<a href='https://cdn.example/comment.jpg'>Download Photo</a>-->")
        with self.assertRaises(engine.DownloadError):
            engine._snapsave_parse(invalid, self.cancel)
        valid = invalid + "<a href='https://cdn.example/real.jpg'>Download Photo</a>"
        self.assertEqual(len(engine._snapsave_parse(valid, self.cancel)), 1)

    def test_snapsave_multiple_assignments_preserve_all_media(self):
        body = ''.join("document.getElementById('download-section').innerHTML="
                       + json.dumps(f'<a href="https://cdn.example/{n}.jpg">Download Photo</a>') + ';'
                       for n in range(3))
        self.assertEqual(len(engine._snapsave_parse(body, self.cancel)), 3)

    def test_snapsave_escaped_fragment_and_js_escapes(self):
        fragment = r'<a href=\"https:\/\/cdn.example\/a.jpg?x=a+b%2f\">Download Photo</a>'
        self.assertEqual(engine._snapsave_parse(fragment, self.cancel)[0].url,
                         'https://cdn.example/a.jpg?x=a+b%2f')
        self.assertEqual(engine._js_unescape(r'\x2B\u0025\/'), '+%/')
        for invalid in ('bad\\', r'\xZZ', r'\u000Z'):
            with self.subTest(invalid=invalid), self.assertRaises(engine.DownloadError):
                engine._js_unescape(invalid)

    def test_snapsave_invalid_packing_and_cancellation(self):
        for body in ('}("abc",0,"abc",0,10,0))',
                     '}("b",0,"abcdefghijk",0,10,0))',
                     '}("k",0,"abcdefghijk",0,10,0))'):
            with self.subTest(body=body), self.assertRaises(engine.DownloadError):
                engine._snapsave_parse(body, self.cancel)
        self.cancel.set()
        with self.assertRaises(engine.DownloadCancelled):
            engine._snapsave_parse(packed('hello'), self.cancel)

    def test_resolver_body_cap_and_cancel(self):
        response = Response(chunks=[b'x' * (1024 * 1024)] * 9)
        with self.assertRaisesRegex(engine.DownloadError, '8 MiB'):
            engine._body(response, self.cancel)
        response = Response(chunks=[b'x' * (1024 * 1024)] * 8)
        self.assertEqual(len(engine._body(response, self.cancel)), 8 * 1024 * 1024)
        response = Response(b'hello', on_chunk=self.cancel.set)
        with self.assertRaises(engine.DownloadCancelled):
            engine._body(response, self.cancel)

    def curlx(self, **kwargs):
        return json.dumps({'platform': 'instagram', 'tweetUrl': POST,
                           'media': [{'type': 'video', 'variants': [
                               {'url': SIGNED, 'contentType': 'video/mp4'}]}], **kwargs}).encode()

    def test_instagram_fallback_post_contract_and_disclosure(self):
        self.send.side_effect = [Response(b"<a href='&dl=1' title='Download Photo'>Download Video</a>"),
                                 Response(self.curlx(), 'application/json')]
        result = self.inspect(self.options(POST + '?igsh=tracking'))
        self.assertEqual(result[0].url, SIGNED)
        self.assertEqual(result[0].kind, 'video')
        first, second = [call.args[0] for call in self.send.call_args_list]
        self.assertEqual(first.url, 'https://snapsave.app/action.php')
        self.assertEqual(first.method, 'POST')
        self.assertIn('url=', first.body)
        self.assertEqual(second.url, 'https://www.curl-x.com/api/extract')
        self.assertEqual(second.headers['User-Agent'], 'Mozilla/5.0')
        self.assertEqual(json.loads(second.body), {'url': POST})
        self.assertTrue(any('third-party' in call.args[0] for call in self.log.call_args_list))
        self.ydl.assert_not_called()

    def test_instagram_snapsave_photo_success_does_not_call_fallback(self):
        self.send.side_effect = None
        self.send.return_value = Response(packed('<a href="https://cdn.example/a.jpg">Download Photo</a>'
                                                 '<a href="https://cdn.example/b.jpg">Download Photo</a>').encode())
        result = self.inspect(self.options(POST))
        self.assertEqual([item.kind for item in result], ['photo', 'photo'])
        self.assertEqual(self.send.call_count, 1)
        self.ydl.assert_not_called()

    def test_cancel_instagram_does_not_fallback(self):
        self.send.side_effect = None
        self.send.return_value = Response(b'bad', on_chunk=self.cancel.set)
        with self.assertRaises(engine.DownloadCancelled):
            self.inspect(self.options(POST))
        self.assertEqual(self.send.call_count, 1)
        self.ydl.assert_not_called()

    def test_curlx_checks_post_platform_and_complete_carousel(self):
        for overrides in ({'platform': 'twitter'}, {'tweetUrl': POST.replace('ABC_123', 'OTHER')},
                          {'tweetUrl': 'https://evil.example/p/ABC_123/'}, {'media': []},
                          {'media': [{'type': 'video', 'variants': [{'url': 'http://cdn.example/a', 'contentType': 'video/mp4'}]}]},
                          {'media': [{'type': 'photo', 'variants': [{'url': SIGNED, 'contentType': 'image/jpeg'}]},
                                     {'type': 'video', 'variants': []}]}):
            with self.subTest(overrides=overrides), self.assertRaises(engine.DownloadError):
                engine._curlx_parse(self.curlx(**overrides).decode(), POST)

    def fake_ydl(self, info=None, payload=MP4, callback=None):
        self.ydl.side_effect = None
        ydl = self.ydl.return_value.__enter__.return_value
        info = info or {'id': 'abc', 'title': 'Test', 'formats': [fmt()]}
        def extract(url, download=False):
            params = self.ydl.call_args.args[0]
            self.assertFalse(download)
            for entry in engine._entries(info):
                list(params['format']({'formats': entry.get('formats', [])}))
            return info
        def filename(entry):
            return self.ydl.call_args.args[0]['outtmpl'].replace('%(ext)s', entry['ext'])
        def process(entry):
            path = Path(filename(entry))
            path.write_bytes(payload)
            hook = self.ydl.call_args.args[0]['progress_hooks'][0]
            if callback:
                callback()
            hook({'status': 'downloading', 'downloaded_bytes': len(payload), 'total_bytes': len(payload)})
        ydl.extract_info.side_effect = extract
        ydl.process_info.side_effect = process
        ydl.prepare_filename.side_effect = filename
        return ydl

    def test_quality_selection_ignores_separate_hls_drm_and_non_mp4(self):
        formats = [fmt('360', 360), fmt('720', 720), fmt('1080', 1080),
                   fmt('separate', 2160, audio='none'), fmt('silent', 2160, audio=None),
                   fmt('hls', 2160, protocol='m3u8_native'), fmt('webm', 2160, ext='webm'),
                   {**fmt('drm', 2160), 'has_drm': True}]
        for quality, expected in (('BEST', '1080'), ('720', '720'), ('480', '360'), ('360', '360')):
            with self.subTest(quality=quality):
                selected = engine._select_format(formats, self.options(quality=quality), self.log)
                self.assertEqual(selected['format_id'], expected)
        self.assertTrue(any('480p' in call.args[0] and '360p' in call.args[0] for call in self.log.call_args_list))

    def test_quality_fallback_above_target_and_unknown_height_is_honest(self):
        self.assertEqual(engine._select_format([fmt(height=720), fmt(height=1080)],
                                              self.options(quality='360'), self.log)['height'], 720)
        self.assertIn('720p', self.log.call_args.args[0])
        engine._select_format([fmt(height=None)], self.options(quality='720'), self.log)
        self.assertIn('unknown height', self.log.call_args.args[0])

    def test_audio_selects_native_audio_only_no_transcode(self):
        formats = [fmt('video', 1080), fmt('m4a', None, ext='m4a', video='none', bitrate=128),
                   fmt('webm', None, ext='webm', video='none', audio='opus', bitrate=160)]
        selected = engine._select_format(formats, self.options(audio_only=True), self.log)
        self.assertEqual(selected['ext'], 'webm')
        with self.assertRaisesRegex(engine.DownloadError, 'native audio-only'):
            engine._select_format([fmt()], self.options(audio_only=True), self.log)
        with self.assertRaisesRegex(engine.DownloadError, 'FFmpeg'):
            engine._select_format([fmt(audio='none')], self.options(), self.log)

    def test_ytdlp_config_never_requests_cookies_or_ffmpeg(self):
        params = engine._ydl_options(self.options(), self.cancel, self.log)
        self.assertTrue(params['noplaylist'])
        self.assertEqual(params['fixup'], 'never')
        self.assertEqual(params['postprocessors'], [])
        self.assertNotIn('cookiefile', params)
        self.assertNotIn('cookiesfrombrowser', params)
        self.assertNotIn('external_downloader', params)
        self.assertEqual(params['socket_timeout'], 15)
        self.cancel.set()
        with self.assertRaises(engine.DownloadCancelled):
            params['progress_hooks'][0]({})
        with self.assertRaises(engine.DownloadCancelled):
            params['logger'].debug('checking cancellation')

    def test_youtube_and_tiktok_unified_inspection(self):
        for url in ('https://www.youtube.com/watch?v=abc', 'https://www.tiktok.com/@u/video/123'):
            self.fake_ydl()
            result = self.inspect(self.options(url))
            self.assertEqual(result[0].source, 'ytdlp')
            self.assertEqual(result[0].url, url)
        self.send.assert_not_called()

    def test_instagram_both_resolvers_fail_then_ytdlp(self):
        self.send.side_effect = [Response(b'no media'), Response(b'{"error":"unavailable"}')]
        self.fake_ydl()
        self.assertEqual(self.inspect(self.options(POST))[0].source, 'ytdlp')
        self.assertTrue(any('trying yt-dlp' in call.args[0] for call in self.log.call_args_list))

    def test_ytdlp_carousel_inspection_does_not_lose_entries(self):
        info = {'entries': [{'id': str(i), 'title': f'Item {i}', 'formats': [fmt()]} for i in range(3)]}
        self.fake_ydl(info)
        result = self.inspect(self.options('https://www.youtube.com/watch?v=abc'))
        self.assertEqual([item.index for item in result], [1, 2, 3])
        self.assertEqual([item.title for item in result], ['Item 0', 'Item 1', 'Item 2'])

    def test_ytdlp_download_combined_and_native_audio_extension(self):
        for audio, data, extension in ((False, MP4, 'mp4'), (True, M4A, 'm4a')):
            options = self.options('https://www.youtube.com/watch?v=abc', audio_only=audio)
            info = {'id': 'abc', 'title': 'Test', 'formats': [fmt(ext=extension, video='none' if audio else 'h264')]}
            ydl = self.fake_ydl(info, payload=data)
            result = self.download([self.item(kind='audio' if audio else 'video', source='ytdlp', url=options.url)], options)
            self.assertEqual(result[0].suffix, '.' + extension)
            self.assertEqual(result[0].read_bytes(), data)
            downloaded = ydl.process_info.call_args.args[0]
            self.assertNotIn('requested_formats', downloaded)
            self.assertNotIn('requested_downloads', downloaded)

    def test_ytdlp_cancel_hook_and_invalid_payload_cleanup(self):
        for data, callback, expected in ((MP4, self.cancel.set, engine.DownloadCancelled),
                                         (b'not media', None, engine.DownloadError)):
            self.cancel.clear()
            self.fake_ydl(payload=data, callback=callback)
            with self.subTest(expected=expected), self.assertRaises(expected):
                self.download([self.item(source='ytdlp')])
            self.assert_empty()

    def test_ytdlp_second_carousel_item_only(self):
        info = {'entries': [{'id': 'one', 'title': 'One', 'formats': [fmt('one')]},
                            {'id': 'two', 'title': 'Two', 'formats': [fmt('two')]}]}
        ydl = self.fake_ydl(info)
        self.download([self.item(source='ytdlp', index=2)])
        self.assertEqual(ydl.process_info.call_args.args[0]['id'], 'two')
        self.assertEqual(ydl.process_info.call_count, 1)

    def test_ytdlp_extraction_error_is_download_error(self):
        self.ydl.side_effect = RuntimeError('extractor unavailable')
        with self.assertRaisesRegex(engine.DownloadError, 'extractor unavailable'):
            self.inspect(self.options('https://youtu.be/abc'))

    def test_actual_ytdlp_format_callable_accepts_combined_stream(self):
        # Exercise yt-dlp's real metadata processing, without contacting a host.
        # The real class is saved at module import below, before setUp patches it.
        fixture: Any = {'id': 'offline', 'title': 'Offline',
                        'extractor': 'test', 'webpage_url': SIGNED,
                        'formats': [fmt('360', 360), fmt('720', 720),
                                    fmt('video-only', 1080, audio='none')]}
        with REAL_YDL(engine._ydl_options(self.options(quality='480'), self.cancel, self.log)) as ydl:
            info: Any = ydl.process_ie_result(fixture, download=False)
        self.assertEqual(info['format_id'], '360')
        self.send.assert_not_called()

    def test_signed_query_survives_real_requests_and_urllib3(self):
        url = SIGNED.replace('https:', 'http:')
        # Stop at HTTPConnection.request, immediately before socket I/O.
        with (
            patch('requests.Session.send', REAL_SEND),
            patch('requests.Session.merge_environment_settings',
                  return_value={'proxies': {}, 'stream': True, 'verify': True, 'cert': None}),
            patch('urllib3.connection.HTTPConnection.request', side_effect=RuntimeError('wire boundary')) as wire,
            self.assertRaisesRegex(RuntimeError, 'wire boundary'),
            engine._response('GET', url, self.cancel),
        ):
            self.fail('Unexpected HTTP response')
        self.assertEqual(wire.call_args.args[1], url.partition('cdn.example')[2])

    def test_redirect_preserves_signed_query(self):
        location = 'https://cdn.example/a%2f.mp4?sig=%61%2fb+z&x=2'
        first = requests.Response()
        first.status_code = 302
        first.url = SIGNED
        first.request = requests.Request('GET', SIGNED).prepare()
        first.headers['Location'] = location
        first._content = b''
        first._content_consumed = True
        second = requests.Response()
        second.status_code = 200
        second.url = location
        second.request = requests.Request('GET', location).prepare()
        second.headers['Content-Type'] = 'video/mp4'
        second.raw = io.BytesIO(MP4)
        with patch('requests.Session.send', REAL_SEND), patch(
                'requests.adapters.HTTPAdapter.send', side_effect=[first, second]) as adapter:
            self.inspect()
        self.assertEqual(adapter.call_args.args[0].url, location)

    def test_real_ytdlp_download_pipeline_without_network_or_ffmpeg(self):
        options = self.options('https://youtu.be/offline')
        def extract(ydl, url, download=False):
            return ydl.process_ie_result({'id': 'offline', 'title': 'Offline',
                                         'extractor': 'test', 'webpage_url': options.url,
                                         'formats': [fmt('360', 360), fmt('720', 720)]}, download=False)
        def transfer(downloader, filename, info):
            Path(filename).write_bytes(MP4)
            downloader._hook_progress({'status': 'finished', 'filename': filename,
                                       'downloaded_bytes': len(MP4), 'total_bytes': len(MP4)}, info)
            return True
        with patch('engine.yt_dlp.YoutubeDL', REAL_YDL), patch.object(
                REAL_YDL, 'extract_info', extract), patch.object(
                REAL_YDL, 'urlopen', side_effect=AssertionError('Unexpected yt-dlp network')), patch(
                'yt_dlp.downloader.http.HttpFD.real_download', transfer), patch(
                'yt_dlp.postprocessor.ffmpeg.FFmpegPostProcessor.run',
                side_effect=AssertionError('Unexpected FFmpeg')):
            result = self.download([self.item(source='ytdlp', url=options.url)], options)
        self.assertEqual(result[0].read_bytes(), MP4)
        self.assertEqual([p.name for p in self.destination.iterdir()], ['Title.mp4'])

    def test_redirect_cancellation_stops_before_next_request(self):
        response = Response(headers={'Location': 'https://cdn.example/next'})
        response.status_code = 302
        def send(*args, **kwargs):
            self.cancel.set()
            return response
        self.send.side_effect = send
        with self.assertRaises(engine.DownloadCancelled):
            self.inspect()
        self.assertEqual(self.send.call_count, 1)
        self.assertTrue(response.closed)

    def test_redirect_bodies_not_consumed_and_relative_url_kept_exact(self):
        first = Response(headers={'Location': '/next.mp4?sig=%61%2f+x'})
        first.status_code = 307
        def fail_body(size):
            raise AssertionError('Redirect body must not be consumed')
        first.iter_content = fail_body
        self.send.side_effect = [first, Response(MP4)]
        self.inspect()
        self.assertTrue(first.closed)
        self.assertEqual(self.send.call_args.args[0].url, 'https://cdn.example/next.mp4?sig=%61%2f+x')

    def test_invalid_redirect_and_redirect_limit(self):
        for location, expected_calls in (('file:///secret', 1), ('https://cdn.example/loop', 11)):
            self.send.reset_mock()
            response = Response(headers={'Location': location})
            response.status_code = 302
            self.send.side_effect = None
            self.send.return_value = response
            with self.subTest(location=location), self.assertRaises(engine.DownloadError):
                self.inspect()
            self.assertEqual(self.send.call_count, expected_calls)

    def test_best_quality_logs_actual_progressive_height(self):
        selected = engine._select_format([fmt('360', 360), fmt('silent', 1080, audio='none')],
                                         self.options(), self.log)
        self.assertEqual(selected['height'], 360)
        self.assertIn('360p', self.log.call_args.args[0])
        self.assertIn('not separate streams', self.log.call_args.args[0])

    def test_native_webm_audio_is_preserved(self):
        data = b'\x1aE\xdf\xa3' + b'webm A_OPUS' * 100
        options = self.options('https://youtu.be/abc', audio_only=True)
        self.fake_ydl({'id': 'audio', 'title': 'Audio',
                       'formats': [fmt(ext='webm', video='none', audio='opus')]}, payload=data)
        result = self.download([self.item(kind='audio', source='ytdlp', url=options.url)], options)
        self.assertEqual(result[0].suffix, '.webm')
        self.assertEqual(result[0].read_bytes(), data)

    def test_empty_and_invalid_items_rejected_before_network(self):
        for items in ([], [self.item(source='other')], [self.item(kind='html')], [self.item(index=0)]):
            with self.subTest(items=items), self.assertRaises(engine.DownloadError):
                engine.download_media(self.options(), items, self.cancel, self.progress, self.log)
        self.send.assert_not_called()
        self.assert_empty()

    def test_windows_reserved_and_empty_names_are_safe(self):
        for name in ('CON', 'con.txt', 'PRN', 'AUX', 'NUL', 'COM1', 'LPT9', 'COM¹'):
            with self.subTest(name=name):
                self.assertTrue(engine._safe_name(name).startswith('_'))
        self.assertEqual(engine._safe_name(' ... '), 'download')
        self.assertEqual(engine._safe_name('a/b\\c:d?e*f<g>h|i\x00'), 'a_b_c_d_e_f_g_h_i_')
        self.assertLessEqual(len(engine._safe_name('x' * 300)), 120)


REAL_SEND = requests.Session.send
REAL_YDL = yt_dlp.YoutubeDL


if __name__ == '__main__':
    unittest.main()
