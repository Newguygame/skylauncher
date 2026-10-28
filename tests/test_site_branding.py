from pathlib import Path
from xml.etree import ElementTree as ET
import json

DOCS = Path(__file__).resolve().parents[1] / "docs"
page = (DOCS / "index.html").read_text(encoding="utf-8")
for name in ("favicon.svg", "favicon.ico", "apple-touch-icon.png",
             "fathom-studios-logo.svg", "site.webmanifest"):
    assert name in page, f"{name} not referenced by webpage"
for name in ("favicon.svg", "fathom-studios-logo.svg"):
    file = DOCS / name
    assert file.is_file(), f"{name} missing"
    root = ET.parse(file).getroot()
    assert root.tag.endswith("svg"), f"{name} is not an SVG"
assert 'src="./icon.png"' not in page, "Old blank logo placeholder remains"
manifest = json.loads((DOCS / "site.webmanifest").read_text(encoding="utf-8"))
assert manifest["short_name"] == "SkyLauncher"
assert manifest["start_url"] == "./"
assert len(manifest["icons"]) >= 2
print("Site branding references, SVGs and manifest OK")
