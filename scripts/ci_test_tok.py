import sys
sys.path.insert(0,'.')
from omega.tokenizer.bpe import OmegaTokenizer
tok = OmegaTokenizer(800)
texts = ['الذكاء الاصطناعي مستقبل البشرية','Python AI programming','def hello(): return 42']
tok.train(texts, min_freq=1, verbose=False)
enc = tok.encode('مرحباً AION')
assert len(enc) > 2
tok.save('/tmp/tok_test')
tok2 = OmegaTokenizer.load('/tmp/tok_test')
print(f"Tokenizer OK | vocab={len(tok.vocab)}")
