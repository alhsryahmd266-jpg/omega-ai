"""
Omega Tokenizer v2 - BPE موسّع
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
✦ 65536 vocab (2x الأول)
✦ يدعم: عربي + إنجليزي + كود + رموز خاصة
✦ Byte-level fallback (لا unknown tokens أبداً)
✦ Special tokens للبرمجة والتفكير
"""

import re
import json
import os
from collections import defaultdict
from typing import List, Dict, Tuple, Optional


SPECIAL_TOKENS = {
    "<pad>": 0, "<bos>": 1, "<eos>": 2, "<unk>": 3,
    "<sep>": 4, "<mask>": 5,
    # Languages
    "<ar>": 6, "<en>": 7,
    # Code
    "<code>": 8, "</code>": 9,
    "<python>": 10, "<kotlin>": 11, "<java>": 12,
    "<js>": 13, "<cpp>": 14, "<bash>": 15,
    # Reasoning
    "<think>": 16, "</think>": 17,
    "<plan>": 18, "</plan>": 19,
    "<step>": 20, "</step>": 21,
    # Dialog
    "<system>": 22, "<user>": 23, "<assistant>": 24,
    # Tasks
    "<task>": 25, "</task>": 26,
    "<tool>": 27, "</tool>": 28,
    "<result>": 29, "</result>": 30,
    # Android/Video
    "<android>": 31, "<video>": 32,
    # Math
    "<math>": 33, "</math>": 34,
}

N_SPECIAL = len(SPECIAL_TOKENS)


class OmegaTokenizer:
    def __init__(self, vocab_size: int = 65536):
        self.vocab_size  = vocab_size
        self.vocab:      Dict[str, int] = {}
        self.inv_vocab:  Dict[int, str] = {}
        self.merges:     Dict[Tuple[str,str], str] = {}
        self.special_tokens = dict(SPECIAL_TOKENS)

        # Byte-level tokens (256 bytes = no unknown)
        self._byte_tokens = {f"<0x{i:02X}>": N_SPECIAL + i for i in range(256)}

    # ── Train ─────────────────────────────────────────────────────────────
    def train(self, texts: List[str], min_freq: int = 2,
              verbose: bool = True):
        if verbose: print(f"Training BPE tokenizer (target: {self.vocab_size})...")

        # Init vocab
        self.vocab = dict(self.special_tokens)
        self.vocab.update(self._byte_tokens)
        idx = len(self.vocab)

        # Word frequencies
        pattern = re.compile(
            r"[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]+"  # Arabic
            r"|[A-Za-z]+"
            r"|[0-9]+"
            r"|[^\s\w]|\s"
        )
        word_freqs: Dict[str, int] = defaultdict(int)
        for text in texts:
            for tok in pattern.findall(text):
                word = ' '.join(self._to_bytes(tok)) + ' </w>'
                word_freqs[word] += 1

        self.vocab['</w>'] = idx; idx += 1

        # BPE merges
        n_merges = self.vocab_size - idx
        vocab_copy = dict(word_freqs)

        for step in range(n_merges):
            pairs = self._get_stats(vocab_copy)
            if not pairs: break
            best = max(pairs, key=lambda p: pairs[p])
            if pairs[best] < min_freq: break
            vocab_copy = self._merge_vocab(best, vocab_copy)
            merged = ''.join(best)
            self.merges[best] = merged
            if merged not in self.vocab:
                self.vocab[merged] = idx; idx += 1
            if verbose and step % 2000 == 0:
                print(f"  merge {step:5d} | vocab: {idx:6d} | best: {''.join(best)!r}")

        self.inv_vocab = {v: k for k, v in self.vocab.items()}
        if verbose: print(f"✅ Tokenizer ready: {len(self.vocab)} tokens")

    def _to_bytes(self, text: str) -> List[str]:
        return [f"<0x{b:02X}>" for b in text.encode('utf-8')]

    def _get_stats(self, vocab):
        pairs = defaultdict(int)
        for word, freq in vocab.items():
            syms = word.split()
            for i in range(len(syms)-1):
                pairs[(syms[i], syms[i+1])] += freq
        return pairs

    def _merge_vocab(self, pair, vocab):
        import re as _re
        new = {}
        bigram = _re.escape(' '.join(pair))
        p = _re.compile(r'(?<!\S)' + bigram + r'(?!\S)')
        for word in vocab:
            new[p.sub(''.join(pair), word)] = vocab[word]
        return new

    # ── Encode ────────────────────────────────────────────────────────────
    def encode(self, text: str,
               add_bos: bool = True,
               add_eos: bool = True,
               max_len: Optional[int] = None) -> List[int]:
        ids = []
        if add_bos: ids.append(self.special_tokens['<bos>'])

        pattern = re.compile(
            r"[\u0600-\u06FF\u0750-\u077F\u08A0-\u08FF]+"
            r"|[A-Za-z]+"
            r"|[0-9]+"
            r"|[^\s\w]|\s"
        )
        for word in pattern.findall(text):
            pieces = self._tokenize_word(word)
            for p in pieces:
                ids.append(self.vocab.get(p, self.special_tokens['<unk>']))

        if add_eos: ids.append(self.special_tokens['<eos>'])
        if max_len: ids = ids[:max_len]
        return ids

    def _tokenize_word(self, word: str) -> List[str]:
        symbols = self._to_bytes(word) + ['</w>']
        if len(symbols) == 1:
            return symbols

        changed = True
        while changed and len(symbols) > 1:
            changed = False
            new_syms = []
            i = 0
            while i < len(symbols)-1:
                pair = (symbols[i], symbols[i+1])
                if pair in self.merges:
                    new_syms.append(self.merges[pair])
                    i += 2; changed = True
                else:
                    new_syms.append(symbols[i]); i += 1
            if i < len(symbols): new_syms.append(symbols[i])
            symbols = new_syms
        return symbols

    def decode(self, ids: List[int], skip_special: bool = True) -> str:
        special_set = set(self.special_tokens.values())
        byte_ids    = {v: k for k, v in self._byte_tokens.items()}
        raw_bytes   = bytearray()
        text_parts  = []

        for id_ in ids:
            if skip_special and id_ in special_set: continue
            piece = self.inv_vocab.get(id_, '')
            if not piece: continue
            # collect byte tokens
            if piece.startswith('<0x') and piece.endswith('>'):
                try: raw_bytes.append(int(piece[3:-1], 16))
                except: pass
            else:
                if raw_bytes:
                    text_parts.append(raw_bytes.decode('utf-8', errors='replace'))
                    raw_bytes = bytearray()
                text_parts.append(piece.replace('</w>', ''))

        if raw_bytes:
            text_parts.append(raw_bytes.decode('utf-8', errors='replace'))

        return ''.join(text_parts).strip()

    def encode_special(self, token_name: str) -> int:
        return self.special_tokens.get(token_name, self.special_tokens['<unk>'])

    # ── Save/Load ─────────────────────────────────────────────────────────
    def save(self, path: str):
        os.makedirs(path, exist_ok=True)
        data = {
            'vocab_size': self.vocab_size,
            'vocab': self.vocab,
            'merges': {f"{a}\t{b}": c for (a,b),c in self.merges.items()},
            'special_tokens': self.special_tokens,
        }
        with open(os.path.join(path, 'tokenizer.json'), 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)
        print(f"Tokenizer saved → {path}/tokenizer.json")

    @classmethod
    def load(cls, path: str) -> 'OmegaTokenizer':
        fp = os.path.join(path, 'tokenizer.json')
        with open(fp, 'r', encoding='utf-8') as f:
            data = json.load(f)
        tok = cls(vocab_size=data['vocab_size'])
        tok.vocab = {k: int(v) for k, v in data['vocab'].items()}
        tok.inv_vocab = {int(v): k for k, v in tok.vocab.items()}
        tok.merges = {tuple(k.split('\t')): v for k, v in data['merges'].items()}
        tok.special_tokens = {k: int(v) for k, v in data['special_tokens'].items()}
        return tok
