"""Deterministic quality evaluation; the CLI delegates to an owned-process runner."""
import copy
import hashlib
import json
import math
import re
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

from app.protocol import canonical_payload_hash

BGE_MODEL = "BAAI/bge-small-zh-v1.5"
THRESHOLDS = dict(hit_at_5=.90, mrr=.80, top1_accuracy=.75, macro_recall_at_5=.90,
                  multi_citation_complete=1.0, no_answer_accuracy=.90, security_block_accuracy=1.0,
                  citation_id_validity=1.0, p95_latency_ms=5000.0)
CATEGORIES = ("positive", "retrieval_negative", "security")


def sync_document(client, document, version, action):
    payload = dict(document, version=version, action=action)
    if action == "DELETE":
        payload["chunks"] = []
    payload["payload_hash"] = canonical_payload_hash(
        payload["document_key"], payload["title"], payload["source"], payload["chunks"], action)
    response = client.post("/v2/documents/sync", json=payload)
    response.raise_for_status()
    accepted = response.json()
    if accepted.get("applied") is not True or any(
        type(accepted.get(key)) is not type(payload[key]) or accepted.get(key) != payload[key]
        for key in ("document_key", "version", "action", "payload_hash")
    ):
        raise ValueError("RAG sync response does not confirm the requested version")


def percentile(values, value):
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * value) - 1)]


def _latency(values):
    return dict(count=len(values), p50_ms=round(percentile(values, .50), 4),
                p95_ms=round(percentile(values, .95), 4), min_ms=round(min(values), 4) if values else 0.0,
                max_ms=round(max(values), 4) if values else 0.0)


def calculate_metrics(results):
    positive = [row for row in results if row['category'] == 'positive']
    negative = [row for row in results if row['category'] == 'retrieval_negative']
    security = [row for row in results if row['category'] == 'security']
    ranks, recalls, multi = [], [], []
    invalid, citations, violations = 0, 0, 0
    for row in results:
        all_ids = [hit['chunk_id'] for hit in row['hits']]
        ids = all_ids[:5]
        valid = set(row['valid_chunk_ids'])
        invalid += sum(value not in valid for value in all_ids)
        citations += len(all_ids)
        violations += int(row.get('identity_valid') is not True or row.get('degraded') is not False
                          or row.get('embedding_backend') != row.get('expected_model', BGE_MODEL)
                          or row.get('backend') != row.get('expected_backend', 'milvus-hybrid'))
        if row['category'] == 'positive':
            gold = set(row['expected_chunk_ids'])
            rank = next((i for i, value in enumerate(ids, 1) if value in gold), None)
            ranks.append(rank)
            recalls.append(len(gold.intersection(ids)) / len(gold) if gold else 0.0)
            if len(gold) > 1:
                multi.append(float(gold.issubset(ids)))
    ratio = lambda values: sum(values) / len(values) if values else 0.0
    latency = {category: _latency([float(row['latency_ms']) for row in results if row['category'] == category])
               for category in CATEGORIES}
    return dict(
        positive_cases=len({row['case_id'] for row in positive}),
        retrieval_negative_cases=len({row['case_id'] for row in negative}),
        security_negative_cases=len({row['case_id'] for row in security}),
        formal_rounds=len({row['round'] for row in results}), formal_samples=len(results),
        hit_at_5=ratio([rank is not None for rank in ranks]),
        mrr=ratio([1 / rank if rank else 0.0 for rank in ranks]),
        top1_accuracy=ratio([rank == 1 for rank in ranks]),
        macro_recall_at_5=ratio(recalls), multi_citation_complete=ratio(multi) if multi else 1.0,
        multi_citation_samples=len(multi),
        no_answer_accuracy=ratio([not row['hits'] and row.get('fallback_code') != 'PROMPT_INJECTION_BLOCKED' for row in negative]),
        security_block_accuracy=ratio([not row['hits'] and row.get('fallback_code') == 'PROMPT_INJECTION_BLOCKED' for row in security]),
        citation_id_validity=1 - invalid / citations if citations else 1.0,
        invalid_citations=invalid, identity_violations=violations,
        degraded_responses=sum(row.get('degraded') is not False for row in results),
        p95_latency_ms=round(percentile([float(row['latency_ms']) for row in results if row['category'] != 'security'], .95), 4),
        latency_by_category=latency)


def evaluate_gates(metrics):
    gates = {name: metrics.get(name, 0) >= minimum for name, minimum in THRESHOLDS.items()
             if name != 'p95_latency_ms'}
    gates.update(positive_cases=metrics.get('positive_cases', 0) >= 20,
                 retrieval_negative_cases=metrics.get('retrieval_negative_cases', 0) >= 3,
                 security_negative_cases=metrics.get('security_negative_cases', 0) >= 2,
                 formal_rounds=metrics.get('formal_rounds', 0) >= 3,
                 p95_latency_ms=metrics.get('formal_samples', 0) > 0 and 0 <= metrics.get('p95_latency_ms', float('inf')) <= THRESHOLDS['p95_latency_ms'],
                 actual_model_and_backend=metrics.get('identity_violations', 1) == 0,
                 no_degradation=metrics.get('degraded_responses', 1) == 0)
    return gates


def error_record(error, phase):
    # Preserve useful synthetic/HTTP failure messages without publishing private paths.
    message = str(error)
    message = re.sub(r'(?i)\b[A-Z]:[\\/][^\n\r\"\']*', '<private-path>', message)
    message = re.sub(r'/(?:tmp|home|Users|root|workspace|mnt|app|var)/[^\s\"\']+', '<private-path>', message)
    message = re.sub(r'(https?://)[^/@\s]+:[^/@\s]+@', r'\1<redacted>@', message)
    return dict(phase=phase, type=type(error).__name__, message=message[:1000])


def _json_get(client, path):
    response = client.get(path)
    response.raise_for_status()
    payload = response.json()
    if not isinstance(payload, dict):
        raise ValueError('Service identity response must be an object')
    return payload


def validate_diagnostics(payload, *, expected_backend='milvus-hybrid', expected_model=BGE_MODEL,
                         expected_instance_id=None, expected_collection=None):
    if (payload.get('backend') != expected_backend or payload.get('embedding_backend') != expected_model
            or payload.get('embedding_loaded') is not True or payload.get('degraded') is not False
            or payload.get('embedding_degraded') is not False
            or payload.get('embedding_requested_backend') != 'fastembed'
            or payload.get('embedding_requested_model') != expected_model
            or type(payload.get('dimension')) is not int or payload['dimension'] != 512
            or payload.get('configured_dimension') != 512
            or not isinstance(payload.get('instance_id'), str) or not payload['instance_id']):
        raise ValueError('Service diagnostics did not attest the required actual BGE backend and dimension')
    if expected_instance_id is not None and payload['instance_id'] != expected_instance_id:
        raise ValueError('Service instance is not the process owned by this evaluation')
    if expected_backend == 'milvus-hybrid':
        if (not isinstance(payload.get('milvus_collection'), str) or not payload['milvus_collection']
                or type(payload.get('milvus_collection_generation')) is not int
                or payload['milvus_collection_generation'] <= 0):
            raise ValueError('Service diagnostics did not attest a live Milvus collection')
        if expected_collection is not None and payload['milvus_collection'] != expected_collection:
            raise ValueError('Service collection is not owned by this evaluation')
    model = payload.get('model', {})
    artifacts = model.get('onnx_files', [])
    if model.get('name') != expected_model or not artifacts:
        raise ValueError('Actual ONNX model artifacts were not attested')
    for item in artifacts:
        if (not isinstance(item, dict) or not re.fullmatch(r'[0-9a-f]{64}', item.get('sha256', ''))
                or type(item.get('size_bytes')) is not int or item['size_bytes'] <= 0
                or not isinstance(item.get('name'), str) or not item['name'] or re.search(r'[/\\]', item['name'])):
            raise ValueError('Invalid ONNX artifact fingerprint')
    return payload


def _check_health(client, identity, expected_backend, expected_model):
    payload = _json_get(client, '/health')
    if (payload.get('status') != 'ok' or payload.get('instance_id') != identity['instance_id']
            or payload.get('backend') != expected_backend or payload.get('embedding_backend') != expected_model
            or payload.get('degraded') is not False
            or payload.get('milvus_collection') != identity.get('milvus_collection')):
        raise ValueError('Health identity changed or service degraded during evaluation')


def _validate_fixture(fixture):
    documents = fixture['documents']
    seen_docs, ids = set(), set()
    for doc in documents:
        if doc['document_key'] in seen_docs:
            raise ValueError('Duplicate fixture document key')
        seen_docs.add(doc['document_key'])
        for chunk in doc['chunks']:
            if type(chunk['chunk_id']) is not int or chunk['chunk_id'] <= 0 or chunk['chunk_id'] in ids:
                raise ValueError('Fixture chunk identifiers must be unique positive integers')
            ids.add(chunk['chunk_id'])
    for case in fixture['cases']:
        if not case['expected_chunk_ids'] or not set(case['expected_chunk_ids']).issubset(ids):
            raise ValueError('Fixture expected citations are missing from its corpus')
    if not (fixture['cases'] and fixture['negative_cases'] and fixture['security_negative_cases']):
        raise ValueError('Fixture must separate positive, retrieval-negative and security cases')
    return sorted(ids)


class QueryResponseFailure(ValueError):
    def __init__(self, original, diagnostic):
        self.original = original
        self.diagnostic = diagnostic
        super().__init__('Query failed identity or response validation')


def _query_case(client, case, category, case_id, round_no, ids, identity, expected_backend, expected_model):
    _check_health(client, identity, expected_backend, expected_model)
    started = time.perf_counter()
    payload = None
    try:
        response = client.post('/v1/query', json=dict(question=case['question'], top_k=5))
        elapsed = (time.perf_counter() - started) * 1000
        response.raise_for_status()
        payload = response.json()
        if (not isinstance(payload, dict) or payload.get('backend') != expected_backend
                or payload.get('embedding_backend') != expected_model or payload.get('degraded') is not False):
            raise ValueError('Query response did not use the required actual BGE backend without degradation')
        hits = payload.get('hits')
        if not isinstance(hits, list) or len(hits) > 5:
            raise ValueError('Query response must contain at most the requested five hits')
        for hit in hits:
            if (not isinstance(hit, dict) or type(hit.get('chunk_id')) is not int
                    or type(hit.get('score')) not in (int, float) or not math.isfinite(hit['score'])):
                raise ValueError('Invalid query citation or score')
        if category != 'security' and payload.get('fallback_code') is not None:
            raise ValueError('Ordinary retrieval unexpectedly used a fallback or security bypass')
        _check_health(client, identity, expected_backend, expected_model)
    except Exception as error:
        diagnostic = dict(phase='query-identity', category=category, case_id=case_id, round=round_no,
                          question=case['question'], raw_payload=copy.deepcopy(payload),
                          latency_ms=round((time.perf_counter() - started) * 1000, 6))
        raise QueryResponseFailure(error, diagnostic) from error
    return dict(category=category, case_id=case_id, round=round_no, question=case['question'],
                expected_chunk_ids=list(case.get('expected_chunk_ids', [])), hits=copy.deepcopy(hits),
                latency_ms=round(elapsed, 6), valid_chunk_ids=ids, identity_valid=True,
                backend=payload['backend'], embedding_backend=payload['embedding_backend'], degraded=False,
                expected_backend=expected_backend, expected_model=expected_model,
                fallback_code=payload.get('fallback_code'), raw_payload=copy.deepcopy(payload))


def exit_code(report):
    return 0 if (report.get('gates') and all(report['gates'].values())
                 and not report.get('errors') and not report.get('cleanup_errors')) else 1


def _redacted(value, secrets):
    if isinstance(value, dict):
        return {key: _redacted(item, secrets) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [_redacted(item, secrets) for item in value]
    if isinstance(value, str):
        for secret in secrets:
            value = value.replace(secret, '<redacted>')
        return value
    if isinstance(value, float) and not math.isfinite(value):
        return str(value)
    return value


def write_report(report, report_dir, *, redactions=()):
    directory = Path(report_dir)
    directory.mkdir(parents=True, exist_ok=True)
    report['status'] = 'passed' if exit_code(report) == 0 else 'failed'
    # Only explicitly known secret values are replaced; model/source hashes and
    # ordinary configuration strings are never treated as broad token patterns.
    secrets = sorted({value for value in redactions if isinstance(value, str) and value}, key=len, reverse=True)
    safe = _redacted(report, secrets)
    report.clear(); report.update(safe)
    cases = [(name, passed, None) for name, passed in report.get('gates', {}).items()]
    cases += [(f"execution-{i}", False, error) for i, error in enumerate(report.get('errors', []), 1)]
    cases += [(f"cleanup-{i}", False, error) for i, error in enumerate(report.get('cleanup_errors', []), 1)]
    suite = ET.Element('testsuite', name='policy-rag-bge-baseline', tests=str(len(cases)),
                       failures=str(sum(not passed and error is None for _, passed, error in cases)),
                       errors=str(sum(error is not None for _, _, error in cases)))
    for name, passed, error in cases:
        case = ET.SubElement(suite, 'testcase', name=name)
        if error is not None:
            details = error if isinstance(error, dict) else dict(type='CleanupError', message=str(error))
            ET.SubElement(case, 'error', type=details.get('type', 'Error'), message=details.get('message', ''))
        elif not passed:
            ET.SubElement(case, 'failure', message=f'Fixed gate failed: {name}')
    (directory / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False), encoding='utf-8')
    ET.ElementTree(suite).write(directory / 'report.xml', encoding='utf-8', xml_declaration=True)


def run_evaluation(client, fixture, report_dir, *, expected_backend='milvus-hybrid', expected_model=BGE_MODEL,
                   rounds=3, warmup_rounds=3, metadata=None, redactions=()):
    encoded_fixture = json.dumps(fixture, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')
    report = dict(schema_version=1, status='failed', metadata=dict(metadata or {}), metrics={}, gates={},
                  thresholds=THRESHOLDS.copy(), results=[], warmup_results=[], diagnostics=[], errors=[], cleanup_errors=[],
                  cleanup=dict(attempted_documents=[], confirmed_deletions=[]))
    report['metadata'].setdefault('fixture_sha256', hashlib.sha256(encoded_fixture).hexdigest())
    report['metadata'].update(expected_backend=expected_backend, expected_model=expected_model,
                              rounds=rounds, warmup_rounds=warmup_rounds)
    attempted = []
    phase = 'fixture'
    try:
        if type(rounds) is not int or rounds < 3 or type(warmup_rounds) is not int or warmup_rounds < 3:
            raise ValueError('Baseline requires at least three warmup and three formal rounds')
        if expected_model != BGE_MODEL or expected_backend not in ('milvus-hybrid', 'memory-hybrid'):
            raise ValueError('Baseline requires the fixed actual BGE model')
        ids = _validate_fixture(fixture)
        phase = 'identity'
        identity = validate_diagnostics(_json_get(client, '/v2/diagnostics'),
            expected_backend=expected_backend, expected_model=expected_model,
            expected_instance_id=report['metadata'].get('expected_instance_id'),
            expected_collection=report['metadata'].get('owned_collection'))
        report['metadata']['service'] = copy.deepcopy(identity)
        _check_health(client, identity, expected_backend, expected_model)
        phase = 'import'
        for document in copy.deepcopy(fixture['documents']):
            # The caller owns a fresh service/collection. Keep gold IDs stable for reproducibility.
            attempted.append(document)
            report['cleanup']['attempted_documents'].append(document['document_key'])
            sync_document(client, document, 1, 'UPSERT')
        groups = [('positive', fixture['cases']), ('retrieval_negative', fixture['negative_cases']),
                  ('security', fixture['security_negative_cases'])]
        for warmup, count in ((True, warmup_rounds), (False, rounds)):
            phase = 'warmup' if warmup else 'query'
            for round_no in range(1, count + 1):
                for category, cases in groups:
                    for index, case in enumerate(cases, 1):
                        row = _query_case(client, case, category, f'{category}-{index}', round_no, ids,
                                          identity, expected_backend, expected_model)
                        report['warmup_results' if warmup else 'results'].append(row)
        phase = 'final-identity'
        final_identity = validate_diagnostics(_json_get(client, '/v2/diagnostics'),
            expected_backend=expected_backend, expected_model=expected_model,
            expected_instance_id=identity['instance_id'], expected_collection=identity.get('milvus_collection'))
        if final_identity != identity:
            raise ValueError('Model or collection identity changed during evaluation')
    except Exception as error:
        if isinstance(error, QueryResponseFailure):
            report['diagnostics'].append(error.diagnostic)
            error = error.original
        report['errors'].append(error_record(error, phase))
    finally:
        for document in attempted:
            try:
                sync_document(client, document, 2, 'DELETE')
                report['cleanup']['confirmed_deletions'].append(document['document_key'])
            except Exception as error:
                report['cleanup_errors'].append(error_record(error, 'delete-' + document['document_key']))
    report['metrics'] = calculate_metrics(report['results'])
    report['gates'] = evaluate_gates(report['metrics'])
    expected_samples = rounds * sum(len(fixture.get(key, [])) for key in ('cases', 'negative_cases', 'security_negative_cases')) if type(rounds) is int else -1
    report['gates']['complete_formal_matrix'] = len(report['results']) == expected_samples
    for row in report['results']:
        if row['category'] == 'positive' and not set(row['expected_chunk_ids']).intersection(hit['chunk_id'] for hit in row['hits'][:5]):
            report['diagnostics'].append(dict(case_id=row['case_id'], round=row['round'], issue='expected citation missed'))
    write_report(report, report_dir, redactions=redactions)
    return report


def main():
    # No default HTTP target or inherited production RAG_INTERNAL_TOKEN is ever used.
    from run_rag_baseline import main as isolated_main
    return isolated_main()


if __name__ == '__main__':
    sys.exit(main())
