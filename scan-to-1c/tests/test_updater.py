import hashlib

import pytest
import requests

from scan_to_1c import updater
from scan_to_1c.updater import Update, UpdateError

NEW_EXE = b"MZ" + b"\x00" * 5000


class FakeResponse:
    def __init__(self, body=None, content=b"", status=200):
        self._body = body
        self._content = content
        self.status_code = status

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError(f"{self.status_code}")

    def json(self):
        return self._body

    def iter_content(self, chunk_size):
        for i in range(0, len(self._content), chunk_size):
            yield self._content[i:i + chunk_size]

    def __enter__(self):
        return self

    def __exit__(self, *exc):
        return False


class FakeSession:
    def __init__(self, releases=None, content=NEW_EXE, status=200):
        self.releases = releases or []
        self.content = content
        self.status = status
        self.urls = []

    def get(self, url, **kwargs):
        self.urls.append(url)
        if url == updater.RELEASES_URL:
            return FakeResponse(body=self.releases, status=self.status)
        return FakeResponse(content=self.content, status=self.status)


def release(tag, assets=("Scan2-1C.exe",), draft=False, prerelease=False, content=NEW_EXE):
    return {
        "tag_name": tag, "draft": draft, "prerelease": prerelease, "body": f"notes {tag}",
        "assets": [{"name": name, "size": len(content),
                    "digest": "sha256:" + hashlib.sha256(content).hexdigest(),
                    "browser_download_url": f"https://example/{tag}/{name}"} for name in assets],
    }


def test_version_compare():
    assert updater.is_newer("1.1.0", "1.0.0")
    assert updater.is_newer("1.10", "1.9.5")
    assert updater.is_newer("2", "1.9")
    assert not updater.is_newer("1.0", "1.0.0")
    assert not updater.is_newer("1.0.0", "1.1.0")
    assert not updater.is_newer("latest", "1.0.0")


def test_find_update_picks_newest_program_release():
    session = FakeSession([
        release("chitalka-v9.0"),  # другой продукт репозитория
        release("scan2onec-v1.2.0", draft=True),
        release("scan2onec-v1.3.0", prerelease=True),
        release("scan2onec-v1.1.0", assets=("Scan2-1C.exe", "Zagruzka-skanov.bsl")),
        release("scan2onec-v1.0.5"),
        release("scan2onec-v1.4.0", assets=("Zagruzka-skanov.bsl",)),  # без exe — пропускаем
    ])
    update = updater.find_update("1.0.0", session=session)
    assert update.version == "1.1.0"
    assert update.url.endswith("/scan2onec-v1.1.0/Scan2-1C.exe")
    assert update.sha256 == hashlib.sha256(NEW_EXE).hexdigest()


def test_find_update_none_when_current_is_latest():
    assert updater.find_update("1.1.0", session=FakeSession([release("scan2onec-v1.1.0")])) is None


def test_find_update_network_error():
    with pytest.raises(UpdateError, match="проверить обновления"):
        updater.find_update("1.0.0", session=FakeSession(status=500))


def make_update(content=NEW_EXE, sha=None):
    return Update("1.1.0", "https://example/Scan2-1C.exe", len(content),
                  sha if sha is not None else hashlib.sha256(content).hexdigest())


def test_download_verifies_checksum(tmp_path):
    target = tmp_path / "new.exe"
    seen = []
    updater.download(make_update(), target, session=FakeSession(), progress=lambda d, t: seen.append(d))
    assert target.read_bytes() == NEW_EXE
    assert seen[-1] == len(NEW_EXE)

    with pytest.raises(UpdateError, match="SHA-256"):
        updater.download(make_update(sha="0" * 64), tmp_path / "bad.exe", session=FakeSession())
    assert not (tmp_path / "bad.exe").exists()
    assert not (tmp_path / "bad.exe.part").exists()


def test_download_rejects_non_program(tmp_path):
    html = b"<html>not found</html>"
    with pytest.raises(UpdateError, match="не программа"):
        updater.download(make_update(html), tmp_path / "x.exe", session=FakeSession(content=html))


def test_install_swaps_exe_and_cleanup(tmp_path):
    exe = tmp_path / "Scan2-1C.exe"
    exe.write_bytes(b"MZ old")
    checked = []
    updater.install(make_update(), exe, session=FakeSession(), check=checked.append)
    assert exe.read_bytes() == NEW_EXE
    assert checked == [tmp_path / "Scan2-1C.exe.new"]
    assert (tmp_path / "Scan2-1C.exe.old").read_bytes() == b"MZ old"
    updater.cleanup_old(exe)
    assert sorted(p.name for p in tmp_path.iterdir()) == ["Scan2-1C.exe"]


def test_install_keeps_old_exe_when_new_one_fails_check(tmp_path):
    exe = tmp_path / "Scan2-1C.exe"
    exe.write_bytes(b"MZ old")

    def broken(_path):
        raise UpdateError("Новая версия не запускается")

    with pytest.raises(UpdateError):
        updater.install(make_update(), exe, session=FakeSession(), check=broken)
    assert exe.read_bytes() == b"MZ old"
    assert sorted(p.name for p in tmp_path.iterdir()) == ["Scan2-1C.exe"]


def test_install_without_write_access_explains(tmp_path, monkeypatch):
    exe = tmp_path / "Scan2-1C.exe"
    exe.write_bytes(b"MZ old")

    def deny(*_args, **_kwargs):
        raise PermissionError("denied")

    monkeypatch.setattr(updater, "download", deny)
    with pytest.raises(UpdateError, match="Нет прав"):
        updater.install(make_update(), exe, session=FakeSession(), check=lambda p: None)
    assert exe.read_bytes() == b"MZ old"


def test_not_frozen_means_no_exe():
    assert updater.current_exe() is None
