#!/usr/bin/env python3
"""Scores infer.py on dictation_en.jsonl, or on the typed sets.

    python3 eval.py MODEL_DIR                 # the shipped recipe, writes results.md
    python3 eval.py MODEL_DIR --compare       # alignment and guard ablations too
    python3 eval.py MODEL_DIR --set typed     # typed_en.jsonl, writes results-typed.md
    python3 eval.py MODEL_DIR --set clean     # clean_en.jsonl, writes results-clean.md

The typed sets are scored the way Writing check offers the model's edits on
typed text, filtered one by one as `WritingCheck.offeredOnTypedText` in both
apps does: a full stop, question or exclamation mark added where a paragraph
stops is taken back, and so is a capital anywhere but a sentence start, or
anywhere in text typed in lower case (see `as_typed`).
"""

from __future__ import annotations

import argparse
import collections
import difflib
import json
import re
import statistics
from pathlib import Path

from infer import LATIN_WORD, PROTECTED, WORD, Tagger

HERE = Path(__file__).parent


def edits(src: str, dst: str) -> set[tuple]:
    a, b = WORD.findall(src), WORD.findall(dst)
    ops = difflib.SequenceMatcher(a=a, b=b, autojunk=False).get_opcodes()
    return {(i1, i2, tuple(b[j1:j2])) for tag, i1, i2, j1, j2 in ops if tag != "equal"}


def protected_items(text: str) -> list[str]:
    """What must survive verbatim: masked spans, numbers, and mid-sentence names."""
    items = [m.group(0) for m in PROTECTED.finditer(text)]
    words = WORD.findall(text)
    for i, w in enumerate(words):
        if any(c.isdigit() for c in w):
            items.append(w)
        elif LATIN_WORD.fullmatch(w) and w[0].isupper() and i > 0 \
                and words[i - 1] not in ".!?" and w != "I" and not w.startswith("I'"):
            items.append(w)
    return items


def damaged(src: str, out: str) -> list[str]:
    """Every protected occurrence the output lost, counted rather than merely
    looked for: "Kevin met Kevin" → "Kevin met Alex" lost one Kevin."""
    src_words = collections.Counter(WORD.findall(src))
    out_words = collections.Counter(WORD.findall(out))
    lost = []
    for item in dict.fromkeys(protected_items(src)):
        if WORD.fullmatch(item):
            wanted, have = src_words[item], out_words[item]
        else:
            wanted, have = src.count(item), out.count(item)
        lost += [item] * max(0, wanted - have)
    return lost


# `WritingText` in both apps, for the typed-text filter: words (letters and
# digits, joined across an apostrophe) and single other characters.
TOKEN = re.compile(r"[^\W_]+(?:['’][^\W_]+)*|\S")
TERMINALS = {".", "!", "?", "…"}
CLOSERS = {'"', "'", "”", "’", ")", "]", "»"}
OPENERS = {'"', "“", "'", "‘", "(", "[", "«"}
LINE_BREAKS = {"\n", "\r", "\u2028", "\u2029"}


def _key(token: str) -> str:
    return token.lower().replace("’", "'")


def _is_word(token: str) -> bool:
    return bool(re.match(r"[^\W_]", token))


def _diff(a: list[str], b: list[str]) -> list[tuple[int, int, int, int]]:
    """`ModelEdits.diff`: changed runs by longest common subsequence, ties
    deleting from `a` first, so the hunks match the keyboards'."""
    prefix = 0
    while prefix < len(a) and prefix < len(b) and a[prefix] == b[prefix]:
        prefix += 1
    suffix = 0
    while suffix < len(a) - prefix and suffix < len(b) - prefix and a[-1 - suffix] == b[-1 - suffix]:
        suffix += 1
    n, m = len(a) - prefix - suffix, len(b) - prefix - suffix
    if n == 0 and m == 0:
        return []
    if n == 0 or m == 0:
        return [(prefix, prefix + n, prefix, prefix + m)]
    lcs = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n - 1, -1, -1):
        for j in range(m - 1, -1, -1):
            lcs[i][j] = lcs[i + 1][j + 1] + 1 if a[prefix + i] == b[prefix + j] else max(lcs[i + 1][j], lcs[i][j + 1])
    hunks, i, j, open_at = [], 0, 0, None
    while i < n or j < m:
        if i < n and j < m and a[prefix + i] == b[prefix + j]:
            if open_at:
                hunks.append((prefix + open_at[0], prefix + i, prefix + open_at[1], prefix + j))
                open_at = None
            i, j = i + 1, j + 1
        elif j >= m or (i < n and lcs[i + 1][j] >= lcs[i][j + 1]):
            open_at = open_at or (i, j)
            i += 1
        else:
            open_at = open_at or (i, j)
            j += 1
    if open_at:
        hunks.append((prefix + open_at[0], prefix + i, prefix + open_at[1], prefix + j))
    return hunks


def _hunks(a: list[str], b: list[str]) -> list[tuple[int, int, int, int]]:
    """`ModelEdits.hunks`: compared without case, so a capital is its own
    edit, and a run of one-for-one swaps split into one per word."""
    out, i, j = [], 0, 0

    def case_only(to_i: int, to_j: int) -> None:
        nonlocal i, j
        while i < to_i and j < to_j:
            if a[i] != b[j]:
                out.append((i, i + 1, j, j + 1))
            i, j = i + 1, j + 1

    for a0, a1, b0, b1 in _diff([_key(t) for t in a], [_key(t) for t in b]):
        case_only(a0, b0)
        if a1 - a0 == b1 - b0 > 1:
            out += [(a0 + k, a0 + k + 1, b0 + k, b0 + k + 1) for k in range(a1 - a0)]
        else:
            out.append((a0, a1, b0, b1))
        i, j = a1, b1
    case_only(len(a), len(b))
    return out


def _sentence_starts(text: str, spans: list[tuple[int, int]], tokens: list[str]) -> set[int]:
    """Where each sentence's first word starts, as `WritingText.sentences` splits them."""
    starts, first, i = set(), 0, 0
    while i < len(tokens):
        last, boundary = i, False
        if tokens[i] in TERMINALS:
            while last + 1 < len(tokens) and tokens[last + 1] in CLOSERS and spans[last + 1][0] == spans[last][1]:
                last += 1
            boundary = last + 1 == len(tokens) or spans[last + 1][0] > spans[last][1]
        gap = text[spans[last][1]:spans[last + 1][0]] if last + 1 < len(tokens) else ""
        if boundary or any(c in LINE_BREAKS for c in gap) or last + 1 == len(tokens):
            k = first
            while k < last and not (_is_word(tokens[k]) and all(t in OPENERS for t in tokens[first:k])):
                k += 1
            starts.add(spans[k][0])
            first = last + 1
        i = last + 1
    return starts


def as_typed(src: str, out: str) -> str:
    """What the keyboard would offer of the model's `out` for `src`.

    Mirrors `WritingCheck.offeredOnTypedText` (Android and iOS): the model's
    edits are found the way `ModelEdits` finds them, and two kinds are taken
    back one by one. A full stop, question or exclamation mark added where a
    paragraph stops (one that replaces a mark the text has is kept), and a
    capital other than "I" anywhere but a sentence start, or anywhere at all
    in text typed in lower case. Every other edit stands as the model made it.
    """
    a_matches, b_matches = list(TOKEN.finditer(src)), list(TOKEN.finditer(out))
    a, b = [m.group() for m in a_matches], [m.group() for m in b_matches]
    a_spans = [m.span() for m in a_matches]
    lowercase_style = next((c.islower() for c in src if c.isalpha()), False)
    starts = _sentence_starts(src, a_spans, a)

    def offered(a0: int, a1: int, b0: int, b1: int) -> bool:
        removed, added = a[a0:a1], b[b0:b1]
        if not removed and added and all(c in ".!?" for c in "".join(added)):
            # The edit sits after the token before it, or before the first one.
            at = a_spans[a0 - 1][1] if a0 > 0 else (a_spans[a0][0] if a0 < len(a) else len(src))
            after = src[at:]
            if not after.strip() or after.lstrip(" \t").startswith("\n"):
                return False
        capital = removed and len(removed) == len(added) and any(map(_is_word, removed + added)) \
            and all(_key(x) == _key(y) for x, y in zip(removed, added))
        if capital and not (_key(removed[0]) == "i" or _key(removed[0]).startswith("i'")):
            return not lowercase_style and a_spans[a0][0] in starts
        return True

    # Start from the model's text and put back what the keyboard would not offer.
    pieces, cursor = [], 0
    for a0, a1, b0, b1 in _hunks(a, b):
        if offered(a0, a1, b0, b1):
            continue
        if b1 > b0:
            pieces.append(out[cursor:b_matches[b0].start()])
            pieces.append(src[a_spans[a0][0]:a_spans[a1 - 1][1]] if a1 > a0 else "")
            cursor = b_matches[b1 - 1].end()
    pieces.append(out[cursor:])
    return "".join(pieces)


def score(tagger: Tagger, cases: list[dict], typed: bool = False) -> dict:
    tp = fp = fn = 0
    clean = clean_changed = noisy = noisy_exact = 0
    damage, lat, fps, misses = [], [], [], []
    for c in cases:
        r = tagger.correct(c["input"], frozenset(c.get("vocabulary", [])))
        lat.append(r.millis)
        out = as_typed(c["input"], r.output) if typed else r.output
        lost = damaged(c["input"], out)
        if lost:
            damage.append((c["input"], out, lost))
        # Edit-level counts cover every case: an edit to a correct sentence is
        # a false positive too, and must cost precision.
        g, h = edits(c["input"], c["expected"]), edits(c["input"], out)
        tp += len(g & h)
        fp += len(h - g)
        fn += len(g - h)
        if c["input"] == c["expected"]:
            clean += 1
            if out != c["input"]:
                clean_changed += 1
                fps.append((c["input"], out))
            continue
        noisy += 1
        noisy_exact += out == c["expected"]
        if out != c["expected"]:
            misses.append((c["input"], out, c["expected"]))
    p = tp / (tp + fp) if tp + fp else 0.0
    rc = tp / (tp + fn) if tp + fn else 0.0
    f = 1.25 * p * rc / (0.25 * p + rc) if p + rc else 0.0
    lat.sort()
    return {
        "n": len(cases), "clean": clean, "noisy": noisy,
        "precision": p, "recall": rc, "f05": f,
        "exact": noisy_exact / noisy if noisy else 0.0,
        "fpr": clean_changed / clean if clean else 0.0,
        "damage": len(damage),
        "p50": statistics.median(lat), "p95": lat[int(0.95 * (len(lat) - 1))],
        "false_positives": fps, "damaged": damage, "misses": misses,
    }


def row(name: str, s: dict) -> str:
    return (f"| {name} | {s['precision']:.3f} | {s['recall']:.3f} | {s['f05']:.3f} | "
            f"{s['exact']:.1%} | {s['fpr']:.1%} | {s['damage']} | "
            f"{s['p50']:.1f} | {s['p95']:.1f} |")


HEADER = ("| Configuration | Precision | Recall | F0.5 | Exact (errors) | "
          "False-positive rate (correct) | Protected-span damage | p50 ms | p95 ms |\n"
          "|---|---|---|---|---|---|---|---|---|")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("model_dir", type=Path)
    ap.add_argument("--compare", action="store_true")
    ap.add_argument("--set", choices=("dictation", "typed", "clean"), default="dictation")
    args = ap.parse_args()
    typed = args.set != "dictation"
    source = HERE / ("dictation_en.jsonl" if not typed else f"{args.set}_en.jsonl")
    cases = [json.loads(l) for l in source.read_text().splitlines() if l.strip()]

    shipped = Tagger(args.model_dir)
    for c in cases[:20]:
        shipped.correct(c["input"])  # warm up before timing
    main_score = score(shipped, cases, typed)
    lines = [HEADER, row("shipped recipe", main_score)]
    if args.compare:
        variants = {
            "no guard or protections, KEEP bias 3.0": dict(guard=False),
            "guard, KEEP bias 0": dict(keep_bias=0.0),
            "guard, KEEP bias 2.0 (the v1.5 recipe)": dict(keep_bias=2.0),
            "guard, KEEP bias 4.0 (Only sure fixes)": dict(keep_bias=4.0),
            "alignment: no [BOS]": dict(alignment="nobos-space-first"),
            "alignment: first word without Ġ": dict(alignment="bos-nospace-first"),
            "alignment: last subtoken": dict(alignment="bos-space-last"),
            "protected spans fed as placeholders": dict(feed_spans=False),
        }
        for name, kw in variants.items():
            lines.append(row(name, score(Tagger(args.model_dir, **kw), cases, typed)))
    table = "\n".join(lines)
    print(table)

    def listing(title, items, fmt):
        return [f"\n### {title} ({len(items)})\n"] + [fmt(*x) for x in items]

    kind = "dictation-style" if not typed else f"{args.set} (typed, as offered)"
    detail = [f"# Results\n\n{main_score['clean']} correct and {main_score['noisy']} "
              f"erroneous {kind} sentences. Generated by `eval.py`.\n", table]
    detail += listing("Correct sentences the stage changed", main_score["false_positives"],
                      lambda a, b: f"- `{a}` → `{b}`")
    detail += listing("Protected spans lost", main_score["damaged"],
                      lambda a, b, l: f"- `{a}` → `{b}` (lost {l})")
    detail += listing("Error sentences not fixed exactly", main_score["misses"],
                      lambda a, b, e: f"- `{a}` → `{b}` (expected `{e}`)")
    name = "results.md" if not typed else f"results-{args.set}.md"
    (HERE / name).write_text("\n".join(detail).rstrip() + "\n")


if __name__ == "__main__":
    main()
