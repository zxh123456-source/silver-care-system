import hashlib
import math
import re
from collections import Counter, defaultdict


def tokenize(text: str) -> list[str]:
    normalized = text.lower()
    chinese = "".join(re.findall(r"[\u4e00-\u9fff]", normalized))
    tokens = [chinese[index : index + 2] for index in range(max(0, len(chinese) - 1))]
    tokens.extend(re.findall(r"[a-z0-9]+", normalized))
    return [token for token in tokens if token]


def hashed_vector(tokens: list[str], dimension: int) -> list[float]:
    vector = [0.0] * dimension
    for token, count in Counter(tokens).items():
        digest = hashlib.blake2b(token.encode("utf-8"), digest_size=8).digest()
        index = int.from_bytes(digest[:4], "big") % dimension
        sign = 1.0 if digest[4] % 2 == 0 else -1.0
        vector[index] += sign * (1.0 + math.log(count))
    norm = math.sqrt(sum(value * value for value in vector))
    return [value / norm for value in vector] if norm else vector


def cosine(left: list[float], right: list[float]) -> float:
    return sum(a * b for a, b in zip(left, right))


class HybridRetriever:
    def __init__(self, dense_dimension: int = 256, embedder=None, dense_min_score: float = 0.05):
        self.dense_dimension = dense_dimension
        self.embedder = embedder
        self.dense_min_score = dense_min_score

    def search(self, question: str, chunks: list[dict], top_k: int) -> list[dict]:
        if not chunks:
            return []
        query_tokens = tokenize(question)
        if not query_tokens:
            return []
        document_tokens = [tokenize(item["content"] + " " + item["title"]) for item in chunks]
        lexical = self._bm25(query_tokens, document_tokens)
        if self.embedder is None:
            query_vector = hashed_vector(query_tokens, self.dense_dimension)
            document_vectors = [hashed_vector(tokens, self.dense_dimension) for tokens in document_tokens]
        else:
            query_vector = self.embedder.embed([question])[0]
            document_vectors = self.embedder.embed([item["title"] + " " + item["content"] for item in chunks])
        dense = [cosine(query_vector, vector) for vector in document_vectors]
        lexical_ranking = sorted(range(len(chunks)), key=lambda index: lexical[index], reverse=True)
        dense_ranking = sorted(range(len(chunks)), key=lambda index: dense[index], reverse=True)
        scores: dict[int, float] = defaultdict(float)
        for rank, index in enumerate(lexical_ranking, start=1):
            if lexical[index] > 0:
                scores[index] += 1.0 / (60 + rank)
        for rank, index in enumerate(dense_ranking, start=1):
            if dense[index] >= self.dense_min_score:
                scores[index] += 1.0 / (60 + rank)
        ranked = sorted(scores, key=lambda index: scores[index], reverse=True)[:top_k]
        return [{**chunks[index], "score": round(scores[index], 8)} for index in ranked]

    def _bm25(self, query: list[str], documents: list[list[str]]) -> list[float]:
        total = len(documents)
        average_length = sum(len(item) for item in documents) / max(total, 1)
        document_frequency: Counter[str] = Counter()
        for document in documents:
            document_frequency.update(set(document))
        scores: list[float] = []
        for document in documents:
            frequencies = Counter(document)
            score = 0.0
            for term in set(query):
                frequency = frequencies.get(term, 0)
                if frequency == 0:
                    continue
                df = document_frequency.get(term, 0)
                inverse = math.log(1 + (total - df + 0.5) / (df + 0.5))
                denominator = frequency + 1.5 * (1 - 0.75 + 0.75 * len(document) / max(average_length, 1))
                score += inverse * frequency * 2.5 / denominator
            scores.append(score)
        return scores
