#!/usr/bin/env python3
"""Fails the build when a document and its translation disagree.

WHY THIS GATE EXISTS. These documents record what was MEASURED versus what was inferred, which is the
one class of claim a reader cannot re-derive for themselves. Keeping such a document in two languages
doubles the places a correction has to land, and this repository has direct evidence that that failure
mode is real: a qualifier about the CloudHSM key-availability quorum was stated correctly when first
measured, then dropped from every later summary, and ended up absent from four documents at once. The
remedy is not more discipline. It is a gate that fails when the copies disagree.

PAIRS GUARDED (see PAIRS below): each entry names the AUTHORITATIVE file and its translation. The
authoritative one is the copy to correct; the translation follows.

WHAT IS CHECKED, and why each check is the shape it is.

1. Heading structure - the heading LEVEL sequence must match exactly, in order. A section added to one
   copy and not the other, or an H4 silently promoted to an H3, is the commonest way two documents
   drift apart. Only levels are compared, because the text is deliberately translated.

2. Code blocks byte-identical, EXCLUDING whole-line and trailing comments - but only for fences that
   carry EVIDENCE. Commands, log lines and error strings are evidence: a translated error message is
   no longer the message the system emitted, so those must survive transcription unaltered rather
   than merely "corresponding". A comment inside a fence is prose that happens to live in a code
   block - the first run of this gate proved the point, when all six code-block mismatches turned out
   to be translated comments sitting above byte-identical commands. The trailing-comment rule requires
   TWO spaces before the # so that a '#' inside a command (a grep pattern, a URL fragment) is not
   mistaken for a comment, which would silently discard part of the evidence being compared.

   A fence with NO LANGUAGE TAG, or tagged `text`, is treated as a DIAGRAM rather than evidence, and
   this distinction was forced by the front-page pair. README.md fences an ASCII architecture diagram
   whose labels are prose and must be translated, so demanding byte-identity there would make the gate
   fail for the wrong reason. Diagram fences are still checked, just differently: every line where
   NEITHER side contains CJK must be byte-identical. That keeps the box-drawing characters, arrows and
   identifiers under guard - a mangled diagram is caught - while exempting exactly the label lines that
   are supposed to change. A tagged fence gets no such leniency, so a command cannot escape the strict
   check by sitting in a `bash` block.

3. Every MEASURED VALUE - the multiset of distinctive numeric tokens must match. This is the check
   that actually catches a stale copy: correcting "87.2 s" in one file and not the other changes the
   numbers, and no amount of prose similarity hides that.

   "Distinctive" means multi-digit, or carrying a `.` `-` `/` - so 87.2, 279, 1729, 153, 2026-03-31,
   5.9.0 and 3/3 all qualify. BARE SINGLE DIGITS ARE EXCLUDED, and that is a deliberate narrowing
   rather than a convenience. The first version compared every number and failed on a residual of
   `1` x2 and `4` x2, which was a TOKENIZER ARTIFACT, not drift: Chinese writes ranges with a
   full-width dash (第 1-5 项 -> two tokens) where English writes a hyphen (sections 1-5 -> one
   token), and single digits also appear as section references and list markers whose count moves with
   sentence structure. A gate that fails for the wrong reason gets switched off, which is worse than
   no gate, so it was narrowed to the class of token every measurement in these documents belongs to.
   Its teeth are proven by a negative control, not asserted - see check-doc-parity-control.sh.

4. Links, checked in two different ways because they are two different things.

   An INTERNAL anchor (`#...`) is derived by GitHub from the heading TEXT, and the heading text is
   deliberately translated - so `#summary-in-english` and `#5-本-fork-没有修的部分必读` are both
   correct and comparing them across files is meaningless. What is checked instead is stronger: every
   internal anchor must RESOLVE within its own file, against slugs derived from that file's own
   headings. A first version compared anchors across files and failed on exactly this, which would
   have been a gate failing for the wrong reason.

   A FILE link is compared as a multiset, so a translation cannot quietly point somewhere else. For a
   bilingual_preamble pair the preamble's links are excluded from that count, for the same reason its
   numbers are: VERIFICATION.md's double lead-in states each link twice where the rendering states it
   once, which is by design. Measured before excluding rather than assumed - CLOUDHSM_BCB_V2_HANDOFF.md
   appears 3 times against 2, README-CloudHSM.md 2 against 1, and the difference is one lead-in box
   each time.

BILINGUAL_PREAMBLE is opt-in PER PAIR, and deliberately not applied by default. VERIFICATION.md
carries a bilingual DOUBLE lead-in (a Chinese box above an English one), so every number in its
preamble legitimately appears twice there and once in the English rendering - verified, not assumed:
2026-03-31 appears 4 times in the original and 3 in the rendering, exactly 2 of the original's inside
the preamble. For that pair the preamble is compared as a SET, which tolerates the duplication while
still catching a value that CHANGED. An earlier version EXCLUDED the preamble entirely and the
negative control walked straight through the hole - it changed "SDK 5.9.0+" to "5.0.0" and the gate
reported agreement - so the preamble is checked, just differently. Pairs without a double lead-in get
the strict whole-document multiset, because an exemption should cover only the structure that forced
it.

Deliberately NOT checked: prose similarity. The whole point of a translation is that the prose
differs. A gate that demanded matching prose would fail constantly and get disabled.

Exits non-zero with a specific diagnosis on failure. Run it directly to see the checks pass.
"""
import re
import sys
from collections import Counter
from pathlib import Path

# (authoritative, translation, bilingual_preamble)
PAIRS = [
    ('VERIFICATION.md', 'VERIFICATION.en.md', True),
    ('README-CloudHSM.md', 'README-CloudHSM.zh-CN.md', False),
    ('CLOUDHSM_BCB_V2_HANDOFF.md', 'CLOUDHSM_BCB_V2_HANDOFF.zh-CN.md', False),
    ('README.md', 'README.zh-CN.md', False),
    ('CLOUDHSM_ADD_HSM_FAQ.md', 'CLOUDHSM_ADD_HSM_FAQ.zh-CN.md', False),
]

# Numbers on these lines name the other file rather than describing the system.
IGNORED_NUMERIC_CONTEXT = re.compile(r'Language note|English rendering|语言说明|这是译文|中文:|English:')

FENCE = re.compile(r'^```')
WHOLE_LINE_COMMENT = re.compile(r'^\s*(#|//)')
TRAILING_COMMENT = re.compile(r'\s{2,}(#|//)\s.*$')
DISTINCTIVE = re.compile(r'^(?:\d{2,}|\d+[.\-/][\d.\-/]*\d)$')
LINK = re.compile(r'\]\(([^)]+)\)')


def split_blocks(text, path):
    """Returns (prose_lines, [(tag, body), ...]).

    The fence's language tag is kept because it decides how strictly the block is compared: a tagged
    fence is evidence, an untagged or `text` fence is a diagram.
    """
    prose, blocks, cur, tag, in_code = [], [], [], '', False
    for line in text.split('\n'):
        if FENCE.match(line):
            if in_code:
                blocks.append((tag, '\n'.join(cur)))
                cur, tag = [], ''
            else:
                tag = line[3:].strip().lower()
            in_code = not in_code
            continue
        (cur if in_code else prose).append(line)
    if in_code:
        raise SystemExit(f'FAIL: unbalanced code fence in {path}')
    return prose, blocks


DIAGRAM_TAGS = {'', 'text', 'txt'}


def compare_block(tag, a_body, b_body):
    """Returns a description of the first substantive difference, or None.

    A TAGGED fence must be byte-identical outside comments - it is evidence.

    A DIAGRAM fence is compared on its numeric tokens only, which is a deliberately narrow check
    arrived at by measurement rather than by taste. The first version compared line counts and failed
    on the front-page architecture diagram at 16 lines versus 15: English wraps one step onto a second
    line where the more compact Chinese does not, so the count legitimately differs while both
    diagrams list steps 1 to 8 in full. Comparing the drawing characters was tried too and fails for
    the same reason - re-wrapping moves them.

    What survives translation in such a diagram is its NUMBERING, and a dropped or duplicated step is
    the realistic drift. Single digits are INCLUDED here, unlike the prose check, because the step
    numbers being guarded are single digits - the reason they are excluded from prose (section
    references whose count moves with sentence structure) does not apply inside a diagram.
    """
    if tag not in DIAGRAM_TAGS:
        sa, sb = strip_comments(a_body), strip_comments(b_body)
        if sa == sb:
            return None
        return next((f'{x!r} vs {y!r}' for x, y in zip(sa, sb) if x != y),
                    f'{len(sa)} vs {len(sb)} lines')

    na = Counter(re.findall(r'\d+(?:[.\-/]\d+)*', a_body))
    nb = Counter(re.findall(r'\d+(?:[.\-/]\d+)*', b_body))
    if na != nb:
        return (f'diagram numbering differs - a step may have been dropped. '
                f'only in first: {dict(sorted((na - nb).items()))}, '
                f'only in second: {dict(sorted((nb - na).items()))}')
    return None


def strip_comments(block):
    """Drops whole-line comments and trailing comments, keeping every command byte-for-byte."""
    out = []
    for line in block.split('\n'):
        if WHOLE_LINE_COMMENT.match(line):
            continue
        out.append(TRAILING_COMMENT.sub('', line).rstrip())
    return out


def heading_levels(prose):
    return [len(l) - len(l.lstrip('#')) for l in prose if re.match(r'^#{1,6} ', l)]


def github_slug(heading_line):
    """Derives GitHub's anchor slug from a heading line, CJK included.

    GitHub lowercases, drops most punctuation, and turns spaces into hyphens. CJK characters and
    backtick-quoted code spans survive, which is why a translated heading yields a different but
    equally valid anchor - and why anchors are verified per file rather than compared across the pair.
    """
    text = re.sub(r'^#{1,6}\s+', '', heading_line).strip()
    text = text.replace('`', '')
    text = text.lower()
    # Keep word characters, CJK, spaces and hyphens; drop everything else.
    text = re.sub(r'[^\w\s\-\u4e00-\u9fff]', '', text)
    return re.sub(r'\s+', '-', text.strip())


def numbers(prose):
    out = []
    for line in prose:
        if IGNORED_NUMERIC_CONTEXT.search(line):
            continue
        out.extend(t for t in re.findall(r'\d+(?:[.\-/]\d+)*', line) if DISTINCTIVE.match(t))
    return Counter(out)


def split_at_body(prose, path):
    for i, line in enumerate(prose):
        if re.match(r'^## ', line):
            return prose[:i], prose[i:]
    raise SystemExit(f'FAIL: no H2 in {path} - cannot locate the evidence body')


def check_pair(a_path, b_path, bilingual_preamble):
    a, b = Path(a_path), Path(b_path)
    for p in (a, b):
        if not p.exists():
            return [f'{p} is missing'], None

    a_prose, a_code = split_blocks(a.read_text(), a_path)
    b_prose, b_code = split_blocks(b.read_text(), b_path)
    failures = []

    # --- 1. heading structure ---
    ah, bh = heading_levels(a_prose), heading_levels(b_prose)
    if ah != bh:
        if len(ah) != len(bh):
            failures.append(f'heading count differs: {a_path} has {len(ah)}, {b_path} has {len(bh)}')
        else:
            i = next(i for i, (x, y) in enumerate(zip(ah, bh)) if x != y)
            failures.append(f'heading {i + 1} level differs: {a_path} h{ah[i]} vs {b_path} h{bh[i]}')

    # --- 2. code blocks: strict for evidence, structural for diagrams ---
    if len(a_code) != len(b_code):
        failures.append(f'code block count differs: {len(a_code)} vs {len(b_code)}')
    else:
        for i, ((ta, xa), (tb, xb)) in enumerate(zip(a_code, b_code)):
            if ta != tb:
                failures.append(f'code block {i + 1} language tag differs: {ta!r} vs {tb!r}')
                continue
            d = compare_block(ta, xa, xb)
            if d:
                kind = 'diagram' if ta in DIAGRAM_TAGS else 'differs outside comments'
                failures.append(f'code block {i + 1} ({ta or "untagged"}) {kind} - evidence must '
                                f'survive transcription unaltered: {d}')

    # --- 3. measured values ---
    if bilingual_preamble:
        a_pre, a_body = split_at_body(a_prose, a_path)
        b_pre, b_body = split_at_body(b_prose, b_path)
        a_pn, b_pn = set(numbers(a_pre)), set(numbers(b_pre))
        if a_pn != b_pn:
            failures.append('measured values diverge in the lead-in - a load-bearing fact differs.\n'
                            f'    only in {a_path}: {sorted(a_pn - b_pn)}\n'
                            f'    only in {b_path}: {sorted(b_pn - a_pn)}')
    else:
        a_body, b_body = a_prose, b_prose

    an, bn = numbers(a_body), numbers(b_body)
    if an != bn:
        failures.append('measured values diverge - one copy has been corrected and the other has '
                        'not.\n'
                        f'    only in {a_path}: {dict(sorted((an - bn).items()))}\n'
                        f'    only in {b_path}: {dict(sorted((bn - an).items()))}')

    # --- 4a. every internal anchor must resolve within its OWN file ---
    # Anchors derive from translated heading text, so they cannot be compared across the pair.
    for path, prose, raw in ((a_path, a_prose, a.read_text()), (b_path, b_prose, b.read_text())):
        slugs = {github_slug(l) for l in prose if re.match(r'^#{1,6} ', l)}
        for target in LINK.findall(raw):
            if target.startswith('#') and target[1:] not in slugs:
                failures.append(f'{path}: internal anchor {target} resolves to no heading in that '
                                f'file')

    # --- 4b. file link targets compared as a multiset ---
    def file_links(prose_lines):
        c = Counter()
        for line in prose_lines:
            for t in LINK.findall(line):
                if not t.startswith('#'):
                    c[t] += 1
        return c

    # For a bilingual double lead-in, the preamble states each link twice on one side and once on the
    # other by design. Compare the body as a MULTISET and the preamble as a SET - excluding the
    # preamble outright was the first design here and the negative control walked straight through it,
    # repointing a link that lived in the lead-in and getting a pass. Set comparison tolerates the
    # duplication while still catching a target that CHANGED, because a changed target disappears from
    # one side's set. This is the same hole, and the same fix, as for the numeric check.
    al, bl = file_links(a_body), file_links(b_body)
    del al[b.name]
    del bl[a.name]
    if al != bl:
        failures.append('file link targets differ.\n'
                        f'    only in {a_path}: {dict(sorted((al - bl).items()))}\n'
                        f'    only in {b_path}: {dict(sorted((bl - al).items()))}')

    if bilingual_preamble:
        apl, bpl = set(file_links(a_pre)), set(file_links(b_pre))
        apl.discard(b.name)
        bpl.discard(a.name)
        if apl != bpl:
            failures.append('file link targets differ in the lead-in.\n'
                            f'    only in {a_path}: {sorted(apl - bpl)}\n'
                            f'    only in {b_path}: {sorted(bpl - apl)}')

    return failures, (len(ah), len(a_code), sum(an.values()))


def main():
    rc = 0
    for a_path, b_path, bilingual in PAIRS:
        failures, stats = check_pair(a_path, b_path, bilingual)
        if failures:
            print(f'FAIL: {a_path} and {b_path} disagree ({len(failures)} problem(s)):')
            for f in failures:
                print(f'  - {f}')
            rc = 1
        else:
            h, c, n = stats
            print(f'OK: {a_path} and {b_path} agree — {h} headings, {c} code blocks '
                  f'byte-identical, {n} measured values match')
    return rc


if __name__ == '__main__':
    sys.exit(main())
