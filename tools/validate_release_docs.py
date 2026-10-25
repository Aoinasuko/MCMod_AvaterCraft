"""Validate the offline guide's files, bilingual sections and local link targets."""
from collections import Counter
from html.parser import HTMLParser
from pathlib import Path
from urllib.parse import unquote, urlsplit

ROOT = Path(__file__).resolve().parents[1] / "release"


class Page(HTMLParser):
    def __init__(self, path):
        super().__init__(convert_charrefs=True)
        self.path, self.ids, self.links, self.languages, self.buttons = path, [], [], [], []
        self.active, self.headings, self.sections = [], [], 0
        self.feed(path.read_text(encoding="utf-8"))

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if "id" in attrs:
            self.ids.append(attrs["id"])
        if attrs.get("data-i18n"):
            self.languages.append(attrs["data-i18n"])
        if attrs.get("data-language-button"):
            self.buttons.append(attrs["data-language-button"])
        if tag == "a" and attrs.get("aria-current") == "page":
            self.active.append(attrs.get("href"))
        if tag == "h1":
            self.headings.append(attrs.get("data-i18n"))
        if tag == "section":
            self.sections += 1
        for attr in ("href", "src"):
            if attrs.get(attr):
                self.links.append(attrs[attr])


def validate():
    pages = {p.name: Page(p) for p in ROOT.glob("*.html")}
    assert set(pages) == {"index.html", "player.html", "npc.html"}, "Expected three guide pages"
    assert not list(ROOT.glob("*.md")) and not list(ROOT.glob("*.txt")), "Obsolete release documents remain"
    for name, page in pages.items():
        assert len(page.ids) == len(set(page.ids)), f"Duplicate anchor in {name}"
        assert set(page.headings) == {"ja", "en"} and len(page.headings) == 2
        assert page.buttons == ["ja", "en"]
        assert page.active == [name]
        language_count = Counter(page.languages)
        assert language_count["ja"] == language_count["en"], f"Missing language block: {name}"
        assert language_count["ja"] > page.sections
        assert {"index.html", "player.html", "npc.html"} <= set(page.links)
        for link in page.links:
            uri = urlsplit(link)
            assert not uri.scheme and not uri.netloc, f"Unexpected network dependency: {link}"
            target = (page.path.parent / unquote(uri.path)).resolve() if uri.path else page.path
            assert ROOT.resolve() in target.parents, f"Link escapes guide: {link}"
            assert target.is_file(), f"Missing file: {name}: {link}"
            if uri.fragment:
                assert target.name in pages and unquote(uri.fragment) in pages[target.name].ids, f"Missing anchor: {name}: {link}"
    for file in ROOT.rglob("*"):
        if file.is_file():
            assert file.suffix in {".html", ".css", ".js"}, f"Unexpected guide file: {file}"
    print("Guide verified: 3 pages, both languages, unique anchors, local links and assets")


if __name__ == "__main__":
    validate()
