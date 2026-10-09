#!/usr/bin/env python3
"""Build Repli's chat n-gram prior from the Synthetic-Persona-Chat corpus (CC BY 4.0).

Usage: build_chat_ngrams.py <csv>... --out repli-en_chat.ngrams [--bigram-min 3] [--trigram-min 3]

Output (gzip text, one record per line):
  S\t<word>:<count>|...              sentence-start words (first token of a message)
  B\t<w1>\t<next>:<count>|...        next-word candidates after one word
  T\t<w1> <w2>\t<next>:<count>|...   next-word candidates after two words
Counts are message-level token counts; candidates are the top-K per context.
"""
import argparse, csv, gzip, re, sys
from collections import Counter, defaultdict

TOKEN = re.compile(r"[a-z]+(?:'[a-z]+)?")
PLACEHOLDER = re.compile(r"\[[^\]]*\]")
SPEAKER = re.compile(r"^\s*user\s*\d+\s*:\s*", re.I)
SENTENCE_END = re.compile(r"[.!?]+")

def messages(paths):
    csv.field_size_limit(10**9)
    for path in paths:
        with open(path, newline="", encoding="utf-8") as fh:
            for row in csv.DictReader(fh):
                conv = row.get("Best Generated Conversation") or row.get("Conversation") or ""
                for line in conv.split("\n"):
                    line = SPEAKER.sub("", line)
                    line = PLACEHOLDER.sub(" ", line)
                    if line.strip():
                        yield line

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", nargs="+")
    ap.add_argument("--out", required=True)
    ap.add_argument("--bigram-min", type=int, default=3)
    ap.add_argument("--trigram-min", type=int, default=3)
    ap.add_argument("--top", type=int, default=5)
    ap.add_argument("--start-top", type=int, default=12)
    args = ap.parse_args()

    starts = Counter(); bigrams = defaultdict(Counter); trigrams = defaultdict(Counter)
    n_msgs = 0
    for message in messages(args.csv):
        n_msgs += 1
        # Split into sentences so "see you later. what time" does not teach "later -> what".
        for sentence in SENTENCE_END.split(message.lower().replace("’", "'")):
            toks = TOKEN.findall(sentence)
            if not toks:
                continue
            starts[toks[0]] += 1
            for i in range(1, len(toks)):
                bigrams[toks[i-1]][toks[i]] += 1
                if i >= 2:
                    trigrams[(toks[i-2], toks[i-1])][toks[i]] += 1

    def top(counter, minimum, k):
        items = [(w, c) for w, c in counter.most_common(k) if c >= minimum]
        return items

    kept_b = kept_t = 0
    with gzip.open(args.out, "wt", encoding="utf-8", compresslevel=9) as out:
        out.write("# Repli chat n-gram prior v1\n")
        out.write("# Source: Synthetic-Persona-Chat (Google, CC BY 4.0), github.com/google-research-datasets/Synthetic-Persona-Chat\n")
        out.write(f"# Messages: {n_msgs}; bigram contexts min count {args.bigram_min}; trigram contexts min count {args.trigram_min}; top {args.top} per context\n")
        start_items = top(starts, 1, args.start_top)
        out.write("S\t" + "|".join(f"{w}:{c}" for w, c in start_items) + "\n")
        for w1 in sorted(bigrams):
            items = top(bigrams[w1], args.bigram_min, args.top)
            if items:
                kept_b += 1
                out.write(f"B\t{w1}\t" + "|".join(f"{w}:{c}" for w, c in items) + "\n")
        for (w1, w2) in sorted(trigrams):
            items = top(trigrams[(w1, w2)], args.trigram_min, args.top)
            if items:
                kept_t += 1
                out.write(f"T\t{w1} {w2}\t" + "|".join(f"{w}:{c}" for w, c in items) + "\n")
    print(f"messages={n_msgs} bigram_contexts={kept_b} trigram_contexts={kept_t} starts={start_items[:12]}", file=sys.stderr)

if __name__ == "__main__":
    main()
