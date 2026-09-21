#!/usr/bin/env python3
"""Fails the build when VERIFICATION.md and VERIFICATION.en.md disagree.

WHY THIS GATE EXISTS. An audit trail records what was measured versus what was inferred, which is
the one class of claim a reader cannot re-derive for themselves. Keeping it in two languages doubles
the places a correction has to land, and this repository has direct evidence that that failure mode
is real: a qualifier about the CloudHSM key-availability quorum was stated correctly when first
measured, then dropped from every later summary, and ended up absent from four documents at once.
The remedy is not more discipline. It is a gate that fails when the copies disagree.

WHAT IS CHECKED, and why each check is the shape it is.

1. Heading structure - the H2/H3 sequence must match exactly, in order. A section added to one copy
   and not the other is the commonest way two documents drift apart.

2. Code blocks byte-identical, EXCLUDING whole-line comments. Commands, log lines and error strings
   are EVIDENCE: a translated error message is no longer the message the system emitted, so those
   must survive transcription unaltered rather than merely "corresponding". A whole-line `#` comment
   inside a fence is prose that happens to live in a code block, and the first run of this gate
   proved the point - all six code-block mismatches were translated comments sitting above
   byte-identical commands. Comparing them would have made the gate fail for the wrong reason.

3. Every MEASURED VALUE in the evidence body - the multiset of distinctive numeric tokens must
   match. This is the check that actually catches a stale copy: correcting "87.2 s" in one file and
   not the other changes the numbers, and no amount of prose similarity hides that.

   "Distinctive" means multi-digit, or carrying a `.` `-` `/` - so 87.2, 279, 1729, 153, 2026-03-31,
   5.9.0 and 3/3 all qualify. BARE SINGLE DIGITS ARE EXCLUDED, and that is a deliberate narrowing
   rather than a convenience. The first version compared every number and failed on a residual of
   `1` x2 and `4` x2, which was a TOKENIZER ARTIFACT, not drift: Chinese writes ranges with a
   full-width dash (第 1-5 项 -> two tokens) where English writes a hyphen (sections 1-5 -> one
   token), and single digits also appear as section references and list markers whose count moves
   with sentence structure. A gate that fails for the wrong reason gets switched off, which is worse
   than no gate, so it was narrowed to the class of token that every measurement in this document
   actually belongs to. Its teeth are proven by a negative control, not asserted - see
   check-verification-parity-control.sh, which perturbs one measured value and requires a failure.

   The lead-in preamble is checked DIFFERENTLY rather than skipped, and that distinction was forced
   by the negative control. VERIFICATION.md carries a deliberate BILINGUAL DOUBLE lead-in (a Chinese
   box above an English one), so every number in it legitimately appears twice there and once in the
   English rendering - verified, not assumed: 2026-03-31 appears 4 times in the original and 3 in
   the rendering, exactly 2 of the original's inside the preamble. The first version handled that by
   excluding the preamble from the check entirely, and the negative control immediately walked
   through the hole: it changed `SDK 5.9.0+` to `5.0.0` and the gate reported agreement. The
   preamble carries load-bearing facts - the hsm1.medium end-of-support date, the minimum SDK, the
   minimum JDK - so it is now compared as a SET of distinctive tokens: set comparison tolerates the
   2x-versus-1x duplication by design while still catching a value that CHANGED, because a changed
   value disappears from one side's set.

Deliberately NOT checked: prose similarity. The whole point of a translation is that the prose
differs. A gate that demanded matching prose would be a gate that fails constantly and gets
disabled, which is worse than no gate at all.

Exits non-zero with a specific diagnosis on failure. Run it directly to see the checks pass.
"""
import re
import sys
from collections import Counter
from pathlib import Path

ZH = Path('VERIFICATION.md')
EN = Path('VERIFICATION.en.md')

# Numbers in the language note name the other file rather than describing the system.
IGNORED_NUMERIC_CONTEXT = re.compile(r'Language note|English rendering|语言说明')

FENCE = re.compile(r'^```')
# A whole-line comment inside a fence is prose, not evidence.
WHOLE_LINE_COMMENT = re.compile(r'^\s*(#|//)')
# A TRAILING comment, by this file's own convention: two or more spaces, then # or //. Split on it
# so the COMMAND is still compared byte-for-byte while its explanation may be translated. Requiring
# two spaces is what keeps a '#' inside a command (a grep pattern, a URL fragment) from being read
# as a comment and silently discarding part of the evidence.
TRAILING_COMMENT = re.compile(r'\s{2,}(#|//)\s.*$')
# A measured value: multi-digit, or carrying a separator. Bare single digits are section references
# and list markers whose count moves with sentence structure - see the module docstring.
DISTINCTIVE = re.compile(r'^(?:\d{2,}|\d+[.\-/][\d.\-/]*\d)$')


def strip_comments(block):
    """Drops whole-line comments and trailing comments, keeping every command byte-for-byte."""
    out = []
    for line in block.split('\n'):
        if WHOLE_LINE_COMMENT.match(line):
            continue
        out.append(TRAILING_COMMENT.sub('', line).rstrip())
    return out


def split_at_body(prose):
    """Returns (preamble, evidence_body) split at the first real H2.

    The two halves are checked differently: the body by multiset, the preamble by set, because the
    original's lead-in is bilingual and duplicates each of its numbers.
    """
    for i, line in enumerate(prose):
        if re.match(r'^## ', line):
            return prose[:i], prose[i:]
    raise SystemExit('FAIL: no H2 found - cannot locate the evidence body')


def split_blocks(text):
    """Returns (prose_lines, code_blocks) with code fences excluded from prose."""
    prose, blocks, cur, in_code = [], [], [], False
    for line in text.split('\n'):
        if FENCE.match(line):
            if in_code:
                blocks.append('\n'.join(cur))
                cur = []
            in_code = not in_code
            continue
        if in_code:
            cur.append(line)
        else:
            prose.append(line)
    if in_code:
        raise SystemExit('FAIL: unbalanced code fence')
    return prose, blocks


def headings(prose):
    return [re.sub(r'\s+', ' ', l.strip()) for l in prose if re.match(r'^#{2,4} ', l.strip())]


def numbers(prose):
    """Measured values appearing in prose, excluding lines about the files themselves."""
    out = []
    for line in prose:
        if IGNORED_NUMERIC_CONTEXT.search(line):
            continue
        # Keep decimals, versions and slashed ratios as single tokens so 87.2 does not become 87, 2.
        out.extend(t for t in re.findall(r'\d+(?:[.\-/]\d+)*', line) if DISTINCTIVE.match(t))
    return Counter(out)


def main():
    for p in (ZH, EN):
        if not p.exists():
            raise SystemExit(f'FAIL: {p} is missing')

    zh_prose, zh_code = split_blocks(ZH.read_text())
    en_prose, en_code = split_blocks(EN.read_text())

    failures = []

    # --- 1. heading structure ---
    zh_h, en_h = headings(zh_prose), headings(en_prose)
    if len(zh_h) != len(en_h):
        failures.append(f'heading count differs: {ZH} has {len(zh_h)}, {EN} has {len(en_h)}')
    else:
        for i, (a, b) in enumerate(zip(zh_h, en_h)):
            # Levels must match; the text is translated so only the '#' prefix is comparable.
            la = len(a) - len(a.lstrip('#'))
            lb = len(b) - len(b.lstrip('#'))
            if la != lb:
                failures.append(f'heading {i + 1} level differs: {a!r} vs {b!r}')

    # --- 2. code blocks byte-identical, whole-line comments aside ---
    if len(zh_code) != len(en_code):
        failures.append(f'code block count differs: {len(zh_code)} vs {len(en_code)}')
    else:
        for i, (a, b) in enumerate(zip(zh_code, en_code)):
            sa, sb = strip_comments(a), strip_comments(b)
            if sa != sb:
                diff = next((f'{x!r} vs {y!r}' for x, y in zip(sa, sb) if x != y),
                            f'{len(sa)} vs {len(sb)} lines')
                failures.append(
                    f'code block {i + 1} differs outside comments - evidence must survive '
                    f'transcription unaltered: {diff}')

    # --- 3. measured values: body by multiset, preamble by set ---
    zh_pre, zh_body = split_at_body(zh_prose)
    en_pre, en_body = split_at_body(en_prose)

    zh_n, en_n = numbers(zh_body), numbers(en_body)
    only_zh = zh_n - en_n
    only_en = en_n - zh_n
    if only_zh or only_en:
        failures.append(
            'measured numbers diverge - one copy has been corrected and the other has not.\n'
            f'    only in {ZH}: {dict(sorted(only_zh.items()))}\n'
            f'    only in {EN}: {dict(sorted(only_en.items()))}')

    # The preamble's numbers are load-bearing (end-of-support date, minimum SDK, minimum JDK) but
    # the original states each of them twice, once per language box - so compare sets, which still
    # catches a value that changed.
    zh_pn, en_pn = set(numbers(zh_pre)), set(numbers(en_pre))
    if zh_pn != en_pn:
        failures.append(
            'measured numbers diverge in the lead-in - a load-bearing fact differs between copies.\n'
            f'    only in {ZH}: {sorted(zh_pn - en_pn)}\n'
            f'    only in {EN}: {sorted(en_pn - zh_pn)}')

    if failures:
        print(f'FAIL: {ZH} and {EN} disagree ({len(failures)} problem(s)):')
        for f in failures:
            print(f'  - {f}')
        return 1

    print(f'OK: {ZH} and {EN} agree — '
          f'{len(zh_h)} headings, {len(zh_code)} code blocks byte-identical, '
          f'{sum(zh_n.values())} measured values in the body match, '
          f'{len(zh_pn)} in the lead-in match')
    return 0


if __name__ == '__main__':
    sys.exit(main())
