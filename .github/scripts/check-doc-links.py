#!/usr/bin/env python3
"""Fails the build when a documentation link points at something that is not there.

WHY THIS GATE EXISTS. These documents cross-reference each other heavily - a handoff points at a
section of the architecture document, a front page points at a specific question in the customer FAQ.
A link that silently stops resolving is how a reader ends up on the wrong page, or on no page, at the
moment they most need the right one. Renaming a heading is enough to do it, and nothing about renaming
a heading suggests you have just broken three links.

WHAT IS CHECKED, across EVERY markdown file in the repository root rather than one hand-picked pair -
the earlier throwaway version of this check only looked at README.md pointing at README-CloudHSM.md,
so a broken link anywhere else was invisible:

  1. Every relative file link resolves to a file that exists.
  2. Every cross-file anchor (`OTHER.md#section`) resolves to a heading in THAT file.
  3. Every same-file anchor (`#section`) resolves to a heading in the file containing it.
  4. Every image path referenced with markdown or an <img> tag exists.

Anchors are derived with GitHub's own slug rules, CJK included, so a translated heading yields a
different but equally valid anchor - which is why anchors are resolved per file and never compared
between a document and its translation.

External links (http/https) and mailto are NOT fetched: a gate that depends on the network fails for
reasons that have nothing to do with this repository.

Exits non-zero, naming every broken link and the file it is in.
"""
import re
import sys
from pathlib import Path

FENCE = re.compile(r'^```')
MD_LINK = re.compile(r'\[[^\]]*\]\(([^)\s]+)\)')
MD_IMAGE = re.compile(r'!\[[^\]]*\]\(([^)\s]+)\)')
HTML_IMG = re.compile(r'<img\s[^>]*src="([^"]+)"')
HEADING = re.compile(r'^(#{1,6})\s+(.*)')
# Markdown does not render a link inside an inline code span, so neither should this gate. These
# documents QUOTE broken upstream markup - `[here](xxx)`, where xxx was never a real target - and
# reading that quotation as a link made the gate report the citation as a defect.
INLINE_CODE = re.compile(r'`[^`]*`')


def github_slug(text):
    """GitHub's anchor derivation: lowercase, drop punctuation, spaces to hyphens, CJK preserved."""
    s = text.strip().replace('`', '').lower()
    s = re.sub(r'[^\w\s\-\u4e00-\u9fff]', '', s)
    return re.sub(r'\s+', '-', s.strip())


def headings_of(path):
    """Slugs of every heading in a file, ignoring anything inside a code fence."""
    slugs, in_code = set(), False
    for line in path.read_text().split('\n'):
        if FENCE.match(line):
            in_code = not in_code
            continue
        if in_code:
            continue
        m = HEADING.match(line)
        if m:
            slugs.add(github_slug(m.group(2)))
    return slugs


def links_of(path):
    """(target, kind) for every link and image outside code fences and inline code spans."""
    out, in_code = [], False
    for raw in path.read_text().split('\n'):
        if FENCE.match(raw):
            in_code = not in_code
            continue
        if in_code:
            continue
        # Blank out inline code spans, keeping the line length so nothing else shifts.
        line = INLINE_CODE.sub(lambda m: ' ' * len(m.group(0)), raw)
        for m in MD_IMAGE.finditer(line):
            out.append((m.group(1), 'image'))
        images = {m.group(1) for m in MD_IMAGE.finditer(line)}
        for m in MD_LINK.finditer(line):
            if m.group(1) not in images:
                out.append((m.group(1), 'link'))
        for m in HTML_IMG.finditer(line):
            out.append((m.group(1), 'image'))
    return out


def main():
    root = Path('.')
    docs = sorted(p for p in root.glob('*.md'))
    if not docs:
        print('FAIL: no markdown files found in the repository root')
        return 1

    slug_cache = {}

    def slugs_for(p):
        if p not in slug_cache:
            slug_cache[p] = headings_of(p)
        return slug_cache[p]

    failures = []
    n_links = 0

    for doc in docs:
        for target, kind in links_of(doc):
            if target.startswith(('http://', 'https://', 'mailto:')):
                continue
            n_links += 1
            file_part, _, anchor = target.partition('#')

            if kind == 'image' or (file_part and not file_part.endswith('.md')):
                p = Path(file_part.lstrip('/'))
                if not p.exists():
                    failures.append(f'{doc}: {kind} target does not exist: {target}')
                continue

            if file_part:
                p = Path(file_part.lstrip('/'))
                if not p.exists():
                    failures.append(f'{doc}: link target does not exist: {target}')
                    continue
                if anchor and anchor not in slugs_for(p):
                    failures.append(f'{doc}: anchor #{anchor} resolves to no heading in {p}')
            elif anchor:
                if anchor not in slugs_for(doc):
                    failures.append(f'{doc}: same-file anchor #{anchor} resolves to no heading')

    if failures:
        print(f'FAIL: {len(failures)} broken link(s) across {len(docs)} document(s):')
        for f in failures:
            print(f'  - {f}')
        return 1
    print(f'OK: {n_links} internal links across {len(docs)} documents all resolve')
    return 0


if __name__ == '__main__':
    sys.exit(main())
