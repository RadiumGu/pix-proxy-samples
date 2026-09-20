#!/usr/bin/env python3
"""
Regenerates images/proxy-cloudhsm-arch.png from the official AWS Architecture Icons.

WHY THIS EXISTS. The diagram used to be a flattened PNG with the icons and every label baked in as
pixels, and no editable source anywhere in the repository. That is precisely why it went stale:
there was no way to refresh an icon or fix a renamed service short of redrawing the whole thing in
an image editor. This script is that missing source. Re-run it when AWS ships a new icon release or
renames a service.

SERVICE RENAMES the old diagram had wrong, caught by matching it against the icon package:
  - "Amazon Kinesis Data Firehose"  ->  "Amazon Data Firehose"   (Arch_Amazon-Data-Firehose)
  - "Amazon QuickSight"             ->  "Amazon Quick"           (Arch_Amazon-Quick)

The on-diagram labels are in English, matching the rest of this repository's documentation; the
previous flattened PNG mixed Portuguese labels into an otherwise English README. "BACEN (SPI)" is
kept as-is, being a proper noun.
Those are not cosmetic: a reader searching the AWS console for the old names finds nothing.

USAGE
    # 1. Download the current icon package from https://aws.amazon.com/architecture/icons/
    #    (this script does NOT vendor the icon set; see LICENSING below)
    # 2. Unzip it, then:
    python3 tools/generate_architecture_diagram.py --icons /path/to/unzipped/package

LICENSING. The AWS Architecture Icons are AWS assets, governed by the terms on the icons page.
This script deliberately does not commit the icon library into the repository; it reads a local
copy and composes the icons into the diagram, which is the ordinary use those assets are published
for. Only the resulting diagram is committed, exactly as the previous flattened PNG already
contained AWS icon artwork.

The layout intentionally reproduces the original diagram's structure and its 1-16 step numbering so
the surrounding README prose stays correct.
"""

import argparse
import glob
import os
import sys

from PIL import Image, ImageDraw, ImageFont

# ----------------------------------------------------------------------------- canvas

CANVAS = (1300, 1300)
BG = "white"
ICON = 100

PROXY_BOX = (520, 20, 1020, 600)
PROXY_FILL = "#CFF5E7"
PROXY_EDGE = "#5A9E88"

OPTIONAL_BOX = (128, 656, 396, 872)
OPTIONAL_FILL = "#E4E4E4"
OPTIONAL_EDGE = "#9A9A9A"

OUTER_EDGE = "#3F3F3F"
TEXT = "#16191F"
ARROW = "#16191F"

FONT_DIRS = ("/usr/share/fonts", "/usr/local/share/fonts",
             os.path.expanduser("~/.fonts"), os.path.expanduser("~/.local/share/fonts"))


def load_font(size, italic=False):
    """Amazon Ember is not publicly distributable; Noto Sans is the closest available fallback."""
    for root in FONT_DIRS:
        for path in glob.glob(os.path.join(root, "**", "*.ttf"), recursive=True):
            name = os.path.basename(path)
            is_italic = "Italic" in name
            # "NotoSans" is a substring of "NotoSans-Italic", so the upright face has to be
            # selected by EXCLUDING the italic one rather than by matching the family name.
            if "NotoSans" in name and is_italic == italic:
                try:
                    return ImageFont.truetype(path, size)
                except OSError:
                    continue
    for root in FONT_DIRS:
        for path in glob.glob(os.path.join(root, "**", "*.ttf"), recursive=True):
            try:
                return ImageFont.truetype(path, size)
            except OSError:
                continue
    return ImageFont.load_default()


# ----------------------------------------------------------------------------- icon lookup

# Service icons, by the icon package's own file naming. Keeping the package's names here is what
# makes a rename visible: if AWS renames a service, this lookup fails loudly instead of silently
# drawing a stale icon.
SERVICE_ICONS = {
    "ssm":       "Arch_AWS-Systems-Manager",
    "secrets":   "Arch_AWS-Secrets-Manager",
    "cloudhsm":  "Arch_AWS-CloudHSM",
    "ec2":       "Arch_Amazon-EC2",
    "elb":       "Arch_Elastic-Load-Balancing",
    "fargate":   "Arch_AWS-Fargate",
    "quick":     "Arch_Amazon-Quick",
    "athena":    "Arch_Amazon-Athena",
    "s3":        "Arch_Amazon-Simple-Storage-Service",
    "firehose":  "Arch_Amazon-Data-Firehose",
}

RESOURCE_ICONS = {
    "glue_crawler": "Res_AWS-Glue_Crawler",
    "glue_catalog": "Res_AWS-Glue_Data-Catalog",
}


def find_icon(icons_root, stem):
    """Prefer the high-resolution @5x PNG the package ships, so no SVG rasterising is needed."""
    patterns = [
        os.path.join(icons_root, "**", "64", f"{stem}_64@5x.png"),
        os.path.join(icons_root, "**", f"{stem}_48@5x.png"),
        os.path.join(icons_root, "**", f"{stem}_48.png"),
        os.path.join(icons_root, "**", f"{stem}_64.png"),
        os.path.join(icons_root, "**", f"{stem}*.png"),
    ]
    for pattern in patterns:
        hits = [h for h in glob.glob(pattern, recursive=True) if "__MACOSX" not in h]
        if hits:
            return sorted(hits, key=lambda p: -os.path.getsize(p))[0]
    return None


def resolve_all(icons_root):
    resolved, missing = {}, []
    for key, stem in list(SERVICE_ICONS.items()) + list(RESOURCE_ICONS.items()):
        path = find_icon(icons_root, stem)
        if path is None:
            missing.append(f"{key} ({stem})")
        else:
            resolved[key] = path
    return resolved, missing


# ----------------------------------------------------------------------------- drawing helpers

def _load_icon(path, size):
    """
    Loads an icon at `size` px.

    Service icons ship a 64@5x PNG (320 px) which downsamples cleanly. Resource icons ship only a
    48 px PNG, which would visibly blur when scaled up to the diagram's icon size, so their SVG is
    rasterised at the target size instead when cairosvg is available.
    """
    svg = os.path.splitext(path)[0] + ".svg"
    img = Image.open(path)
    if img.width < size and os.path.exists(svg):
        try:
            import cairosvg
            import io
            png = cairosvg.svg2png(url=svg, output_width=size * 3, output_height=size * 3)
            img = Image.open(io.BytesIO(png))
        except Exception:
            img = Image.open(path)  # sharpness is not worth failing the build over
    return img.convert("RGBA").resize((size, size), Image.LANCZOS)


def paste_icon(canvas, path, center, size=ICON):
    icon = _load_icon(path, size)
    canvas.paste(icon, (center[0] - size // 2, center[1] - size // 2), icon)


def centered_text(draw, center_x, top_y, lines, font, fill=TEXT):
    y = top_y
    for line in lines:
        w = draw.textbbox((0, 0), line, font=font)[2]
        draw.text((center_x - w / 2, y), line, font=font, fill=fill)
        y += font.size + 4
    return y


def step(draw, xy, number, font):
    """The bold 1-16 markers the README's numbered walkthrough refers to."""
    label = str(number)
    w, h = draw.textbbox((0, 0), label, font=font)[2:]
    draw.text((xy[0] - w / 2, xy[1] - h / 2), label, font=font, fill=TEXT)


def arrow(draw, start, end, width=3, head=11, colour=ARROW, double=False):
    draw.line([start, end], fill=colour, width=width)
    _head(draw, start, end, head, colour)
    if double:
        _head(draw, end, start, head, colour)


def _head(draw, start, end, head, colour):
    import math
    angle = math.atan2(end[1] - start[1], end[0] - start[0])
    for sign in (1, -1):
        a = angle + sign * math.radians(154)
        draw.line([end, (end[0] + head * math.cos(a), end[1] + head * math.sin(a))],
                  fill=colour, width=3)


def elbow(draw, start, end, via_x=None, via_y=None, **kw):
    """Right-angled connector, matching the original diagram's orthogonal routing."""
    if via_x is not None:
        mid1, mid2 = (via_x, start[1]), (via_x, end[1])
    else:
        mid1, mid2 = (start[0], via_y), (end[0], via_y)
    draw.line([start, mid1], fill=ARROW, width=3)
    draw.line([mid1, mid2], fill=ARROW, width=3)
    arrow(draw, mid2, end, **kw)


def building(draw, center, w=96, h=118):
    """BACEN (SPI) is not an AWS service, so it keeps a neutral institution glyph."""
    x0, y0 = center[0] - w // 2, center[1] - h // 2
    draw.rectangle([x0, y0 + 22, x0 + int(w * 0.62), y0 + h], fill="#4A4A4A")
    draw.rectangle([x0 + int(w * 0.62), y0, x0 + w, y0 + h], fill="#2F2F2F")
    for row in range(5):
        for col in range(3):
            wx = x0 + 9 + col * 15
            wy = y0 + 32 + row * 17
            draw.rectangle([wx, wy, wx + 8, wy + 9], fill="white")
    for row in range(6):
        wx = x0 + int(w * 0.62) + 11
        wy = y0 + 12 + row * 17
        draw.rectangle([wx, wy, wx + 12, wy + 9], fill="white")


# ----------------------------------------------------------------------------- the diagram

def build(icons):
    canvas = Image.new("RGB", CANVAS, BG)
    draw = ImageDraw.Draw(canvas)

    f_label = load_font(19)
    f_small = load_font(17)
    f_step = load_font(30)
    f_group = load_font(23)

    draw.rectangle([18, 18, CANVAS[0] - 18, CANVAS[1] - 18], outline=OUTER_EDGE, width=3)

    # Groups first, so nodes land on top of their fill.
    draw.rectangle(PROXY_BOX, fill=PROXY_FILL, outline=PROXY_EDGE, width=3)
    centered_text(draw, (PROXY_BOX[0] + PROXY_BOX[2]) // 2, PROXY_BOX[1] + 14, ["Proxy"], f_group)
    draw.rectangle(OPTIONAL_BOX, fill=OPTIONAL_FILL, outline=OPTIONAL_EDGE, width=2)
    centered_text(draw, (OPTIONAL_BOX[0] + OPTIONAL_BOX[2]) // 2, OPTIONAL_BOX[1] + 10,
                  ["Optional"], f_group)

    # ---- node positions (icon centres)
    p_ssm      = (170, 110)
    p_secrets  = (420, 110)
    p_hsm      = (770, 190)
    p_app      = (330, 430)
    p_elb      = (620, 430)
    p_fargate  = (900, 430)
    p_bacen    = (1180, 430)
    p_quick    = (262, 760)
    p_athena   = (510, 760)
    p_s3       = (700, 760)
    p_firehose = (900, 760)
    p_crawler  = (700, 960)
    p_catalog  = (700, 1150)

    # ---- arrows (drawn before icons so lines tuck under the glyphs)
    # request path: app -> ELB -> Fargate -> BACEN
    arrow(draw, (p_app[0] + 56, p_elb[1] - 22), (p_elb[0] - 56, p_elb[1] - 22))
    arrow(draw, (p_elb[0] + 56, p_elb[1] - 22), (p_fargate[0] - 56, p_elb[1] - 22))
    # response path
    arrow(draw, (p_elb[0] - 56, p_elb[1] + 24), (p_app[0] + 56, p_elb[1] + 24))
    arrow(draw, (p_fargate[0] - 56, p_elb[1] + 24), (p_elb[0] + 56, p_elb[1] + 24))
    # mTLS to BACEN, both directions
    arrow(draw, (p_fargate[0] + 56, p_fargate[1]), (p_bacen[0] - 62, p_fargate[1]), double=True)
    # Fargate -> CloudHSM (sign / verify)
    elbow(draw, (p_fargate[0], p_fargate[1] - 58), (p_hsm[0] + 58, p_hsm[1]), via_y=p_hsm[1])
    # Fargate -> Firehose (log)
    arrow(draw, (p_fargate[0], p_fargate[1] + 58), (p_firehose[0], p_firehose[1] - 58))
    # Firehose -> S3
    arrow(draw, (p_firehose[0] - 58, p_firehose[1]), (p_s3[0] + 58, p_s3[1]))
    # Athena -> S3
    arrow(draw, (p_athena[0] + 58, p_athena[1]), (p_s3[0] - 58, p_s3[1]))
    # Quick -> Athena
    arrow(draw, (p_quick[0] + 58, p_quick[1]), (p_athena[0] - 58, p_athena[1]))
    # Firehose -> Glue Catalog, and Athena -> Glue Catalog
    # Starts below the two-line label, so the vertical run does not strike through "Firehose".
    elbow(draw, (p_firehose[0], p_firehose[1] + 120), (p_catalog[0] + 62, p_catalog[1]),
          via_y=p_catalog[1])
    elbow(draw, (p_athena[0], p_athena[1] + 100), (p_catalog[0] - 62, p_catalog[1]),
          via_y=p_catalog[1])
    # Crawler <-> Catalog
    arrow(draw, (p_crawler[0], p_crawler[1] + 100), (p_catalog[0], p_catalog[1] - 62), double=True)

    # ---- icons + labels
    def node(pos, key, lines, resource=False):
        paste_icon(canvas, icons[key], pos)
        centered_text(draw, pos[0], pos[1] + ICON // 2 + 8, lines, f_label if not resource else f_small)

    node(p_ssm, "ssm", ["AWS Systems Manager", "Parameter Store"])
    node(p_secrets, "secrets", ["AWS Secrets Manager"])
    node(p_hsm, "cloudhsm", ["AWS CloudHSM"])
    node(p_app, "ec2", ["Service /", "application"])
    node(p_elb, "elb", ["ELB"])
    node(p_fargate, "fargate", ["AWS Fargate", "(Proxy)", "(Signing + mTLS)"])
    node(p_quick, "quick", ["Amazon Quick"])
    node(p_athena, "athena", ["Amazon Athena"])
    node(p_s3, "s3", ["Amazon S3"])
    node(p_firehose, "firehose", ["Amazon Data", "Firehose"])
    node(p_crawler, "glue_crawler", ["Glue Crawler"], resource=True)
    node(p_catalog, "glue_catalog", ["Glue Catalog"], resource=True)

    building(draw, p_bacen)
    centered_text(draw, p_bacen[0], p_bacen[1] + 70, ["BACEN (SPI)"], f_label)

    # ---- edge labels
    centered_text(draw, 475, p_elb[1] - 48, ["XML Req"], f_small)
    centered_text(draw, 760, p_elb[1] - 48, ["XML Req"], f_small)
    centered_text(draw, 475, p_elb[1] + 30, ["XML Resp"], f_small)
    centered_text(draw, 760, p_elb[1] + 30, ["XML Resp"], f_small)
    centered_text(draw, 1055, p_fargate[1] - 30, ["mTLS"], f_small)
    centered_text(draw, 845, 630, ["Log"], f_small)

    # ---- step numbers, matching the README walkthrough
    step(draw, (420, 215), 1, f_step)
    step(draw, (700, 170), 2, f_step)
    step(draw, (170, 238), 3, f_step)
    step(draw, (475, 372), 4, f_step)
    step(draw, (760, 372), 5, f_step)
    step(draw, (900, 300), 6, f_step)
    step(draw, (975, 372), 7, f_step)
    step(draw, (975, 470), 8, f_step)
    step(draw, (925, 630), 9, f_step)
    step(draw, (760, 500), 10, f_step)
    step(draw, (475, 500), 11, f_step)
    step(draw, (955, 990), 12, f_step)
    step(draw, (812, 700), 13, f_step)
    step(draw, (487, 1010), 14, f_step)
    step(draw, (790, 1060), 15, f_step)
    step(draw, (600, 700), 16, f_step)

    return canvas


def build_banner(icons):
    """The three-icon strip at the top of README-CloudHSM.md: CloudHSM, Fargate, ELB."""
    size, gap = 132, 14
    keys = ("cloudhsm", "fargate", "elb")
    width = size * len(keys) + gap * (len(keys) - 1)
    canvas = Image.new("RGB", (width, size), BG)
    for i, key in enumerate(keys):
        icon = _load_icon(icons[key], size)
        canvas.paste(icon, (i * (size + gap), 0), icon)
    return canvas


def build_tile(icons, key, size=200):
    """The square service tile README.md uses to link to each architecture."""
    canvas = Image.new("RGB", (size, size), BG)
    icon = _load_icon(icons[key], size)
    canvas.paste(icon, (0, 0), icon)
    return canvas


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--icons", required=True,
                        help="root of the unzipped AWS Architecture Icons package")
    parser.add_argument("--out", default="images/proxy-cloudhsm-arch.png")
    parser.add_argument("--banner", default=None,
                        help="also write the README header strip, e.g. images/proxy-cloudhsm.png")
    parser.add_argument("--tile", default=None,
                        help="also write the CloudHSM tile, e.g. images/hsm.jpg")
    args = parser.parse_args()

    if not os.path.isdir(args.icons):
        sys.exit(f"icon package directory not found: {args.icons}")

    icons, missing = resolve_all(args.icons)
    if missing:
        sys.exit("could not find these icons in the package - AWS may have renamed the service, "
                 "in which case update SERVICE_ICONS/RESOURCE_ICONS and the on-diagram label:\n  "
                 + "\n  ".join(missing))

    releases = sorted(d for d in os.listdir(args.icons)
                      if d.startswith("Architecture-Service-Icons"))
    release = releases[0] if releases else "unknown"
    print(f"icon release: {release}")
    for key in sorted(icons):
        print(f"  {key:14s} {os.path.relpath(icons[key], args.icons)}")

    canvas = build(icons)
    os.makedirs(os.path.dirname(args.out) or ".", exist_ok=True)
    canvas.save(args.out, "PNG", optimize=True)
    print(f"wrote {args.out} ({canvas.size[0]}x{canvas.size[1]})")

    if args.banner:
        banner = build_banner(icons)
        banner.save(args.banner, "PNG", optimize=True)
        print(f"wrote {args.banner} ({banner.size[0]}x{banner.size[1]})")

    if args.tile:
        tile = build_tile(icons, "cloudhsm")
        tile.save(args.tile, "JPEG", quality=95)
        print(f"wrote {args.tile} ({tile.size[0]}x{tile.size[1]})")


if __name__ == "__main__":
    main()
