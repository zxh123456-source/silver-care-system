"""Run the frozen BGE baseline against a service and namespace owned by this invocation."""
import argparse
import contextlib
import hashlib
import json
import os
import platform
import re
import secrets
import socket
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

import httpx

from evaluate_rag import (BGE_MODEL, calculate_metrics, error_record, evaluate_gates, exit_code,
                          run_evaluation, validate_diagnostics, write_report)

RETRIEVAL_CONFIG = dict(semantic_dense_min_score=0.55, hashed_dense_min_score=0.05, top_k=5)


def stop_owned_process(process, timeout=10):
    if process.poll() is None:
        process.terminate()
    try:
        process.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=timeout)
    if process.poll() is None:
        raise RuntimeError('Owned evaluation process did not stop')


def drop_owned_collection(client, collection, owner_prefix):
    if (not re.fullmatch(r'silvercare_eval_[0-9a-f]{32}', owner_prefix)
            or collection != owner_prefix + '_v2'):
        raise ValueError('Refusing to delete a collection outside this evaluation ownership')
    if client.has_collection(collection_name=collection):
        client.drop_collection(collection_name=collection)
    if client.has_collection(collection_name=collection):
        raise RuntimeError('Owned collection cleanup could not be confirmed')


def _sanitize_log(path, redactions):
    if not path.exists():
        return
    content = path.read_text(encoding='utf-8', errors='replace')
    for value in sorted({str(value) for value in redactions if value}, key=len, reverse=True):
        content = content.replace(value, '<redacted>')
        content = content.replace(value.replace('\\', '/'), '<redacted>')
    content = re.sub(r'(?i)\b[A-Z]:[\\/][^\n\r\"\']*', '<private-path>', content)
    content = re.sub(r'/(?:tmp|home|Users|root|workspace|mnt|app|var)/[^\s\"\']+', '<private-path>', content)
    path.write_text(content, encoding='utf-8')


def _http_identity(client, expected_instance, expected_collection, backend):
    response = client.get('/v2/diagnostics')
    response.raise_for_status()
    metadata = response.json()
    validate_diagnostics(metadata, expected_backend=backend + '-hybrid', expected_model=BGE_MODEL,
                         expected_instance_id=expected_instance,
                         expected_collection=expected_collection if backend == 'milvus' else None)
    response = client.get('/health')
    response.raise_for_status()
    health = response.json()
    if (health.get('instance_id') != expected_instance or health.get('status') != 'ok'
            or health.get('backend') != backend + '-hybrid' or health.get('embedding_backend') != BGE_MODEL
            or health.get('degraded') is not False
            or health.get('milvus_collection') != metadata.get('milvus_collection')):
        raise ValueError('Owned service health does not match the expected process and BGE identity')
    return metadata


class OwnedServiceFailure(RuntimeError):
    def __init__(self, original, manifest):
        self.original = original
        self.manifest = manifest
        super().__init__('Owned service evaluation failed; original error and cleanup evidence are retained')


@contextlib.contextmanager
def create_owned_service(*, report_dir, backend, milvus_uri, milvus_token, startup_timeout=600):
    if backend not in ('milvus', 'memory'):
        raise ValueError('Evaluation backend must be milvus or memory')
    if backend == 'milvus' and (not milvus_uri or not milvus_token):
        raise ValueError('Configure dedicated RAG_TEST_MILVUS_URI and RAG_TEST_MILVUS_TOKEN')
    directory = Path(report_dir)
    directory.mkdir(parents=True, exist_ok=True)
    owner_prefix = 'silvercare_eval_' + uuid.uuid4().hex
    collection = owner_prefix + '_v2'
    instance_id = uuid.uuid4().hex
    internal_token = secrets.token_urlsafe(48)
    cleanup_errors, cleanup = [], dict(process_stopped=False, collection_removed=backend != 'milvus', data_removed=False)
    manifest = dict(client=None, metadata=dict(expected_instance_id=instance_id,
                    owned_collection=collection if backend == 'milvus' else None, retrieval_config=RETRIEVAL_CONFIG.copy()),
                    cleanup_errors=cleanup_errors, cleanup=cleanup,
                    redactions=[internal_token, milvus_token, milvus_token.split(':', 1)[-1] if milvus_token else None])
    process = client = probe = None
    temporary = tempfile.TemporaryDirectory(prefix='silvercare-rag-eval-')
    log_path = directory / 'service.log'
    log_handle = None
    startup_failure = None
    proxy_restore = {}
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as listener:
            listener.bind(('127.0.0.1', 0))
            port = listener.getsockname()[1]
        environment = os.environ.copy()
        # Explicitly override all settings that could point this child at daily data.
        environment.update(RAG_INTERNAL_TOKEN=internal_token, RAG_INSTANCE_ID=instance_id,
                           RAG_BACKEND=backend, RAG_DATA_FILE=str(Path(temporary.name) / 'documents.json'),
                           RAG_MILVUS_COLLECTION=owner_prefix, RAG_MILVUS_URI=milvus_uri or 'http://127.0.0.1:1',
                           RAG_MILVUS_TOKEN=milvus_token or '', RAG_EMBEDDING_BACKEND='fastembed',
                           RAG_EMBEDDING_MODEL=BGE_MODEL, RAG_DENSE_DIMENSION='512',
                           RAG_SEMANTIC_DENSE_MIN_SCORE='0.55', RAG_HASHED_DENSE_MIN_SCORE='0.05', RAG_DEFAULT_TOP_K='5',
                           PYTHONUTF8='1', PYTHONDONTWRITEBYTECODE='1')
        # Do not silently substitute a production model token or a production endpoint.
        environment.pop('RAG_EVAL_URL', None)
        if backend == 'milvus':
            from urllib.parse import urlparse
            from pymilvus import MilvusClient
            host = urlparse(milvus_uri).hostname or '127.0.0.1'
            bypass = {item.strip() for item in environment.get('NO_PROXY', '').split(',') if item.strip()}
            bypass.update({host, '127.0.0.1', 'localhost'})
            for key in ('NO_PROXY', 'no_proxy', 'no_grpc_proxy'):
                environment[key] = ','.join(sorted(bypass))
                proxy_restore[key] = os.environ.get(key)
                os.environ[key] = environment[key]
            # Creating a client does not create or delete application collections.
            probe = MilvusClient(uri=milvus_uri, token=milvus_token, timeout=10)
        log_handle = log_path.open('w', encoding='utf-8')
        command = [sys.executable, '-m', 'uvicorn', 'app.main:app', '--host', '127.0.0.1', '--port', str(port)]
        kwargs = dict(cwd=str(Path(__file__).resolve().parent), env=environment, stdin=subprocess.DEVNULL,
                      stdout=log_handle, stderr=subprocess.STDOUT)
        if os.name == 'nt':
            kwargs['creationflags'] = subprocess.CREATE_NO_WINDOW
        process = subprocess.Popen(command, **kwargs)
        manifest['metadata'].update(owned_process_id=process.pid)
        client = httpx.Client(base_url=f'http://127.0.0.1:{port}',
                              headers={'X-Internal-Token': internal_token}, timeout=20, trust_env=False)
        manifest['client'] = client
        deadline = time.monotonic() + startup_timeout
        while True:
            if process.poll() is not None:
                raise RuntimeError('Owned RAG process exited before reporting its required identity; inspect service.log')
            try:
                metadata = _http_identity(client, instance_id, collection, backend)
                manifest['metadata']['service'] = metadata
                break
            except (httpx.ConnectError, httpx.ReadTimeout):
                if time.monotonic() >= deadline:
                    raise TimeoutError('Owned RAG service did not become ready before the startup deadline')
                time.sleep(.2)
        yield manifest
    except BaseException as error:
        startup_failure = error
        raise
    finally:
        if client is not None:
            try:
                client.close()
            except Exception as error:
                cleanup_errors.append(error_record(error, 'http-client-close'))
        if process is not None:
            try:
                stop_owned_process(process)
                cleanup['process_stopped'] = True
            except Exception as error:
                cleanup_errors.append(error_record(error, 'owned-process-stop'))
        else:
            cleanup['process_stopped'] = True
        if probe is not None:
            try:
                drop_owned_collection(probe, collection, owner_prefix)
                cleanup['collection_removed'] = True
            except Exception as error:
                cleanup_errors.append(error_record(error, 'owned-collection-drop'))
            try:
                probe.close()
            except Exception as error:
                cleanup_errors.append(error_record(error, 'milvus-client-close'))
        elif backend == 'milvus':
            # Before the child starts there can be no generated collection to remove.
            cleanup['collection_removed'] = process is None
        try:
            temporary.cleanup()
            cleanup['data_removed'] = not Path(temporary.name).exists()
            if not cleanup['data_removed']:
                raise RuntimeError('Temporary document manifest cleanup could not be confirmed')
        except Exception as error:
            cleanup_errors.append(error_record(error, 'owned-data-remove'))
        if log_handle is not None:
            try:
                log_handle.close()
            except Exception as error:
                cleanup_errors.append(error_record(error, 'service-log-close'))
        try:
            _sanitize_log(log_path, [internal_token, milvus_token, temporary.name, sys.prefix, Path(__file__).resolve().parent,
                                     os.environ.get('FASTEMBED_CACHE_PATH', '')])
        except Exception as error:
            cleanup_errors.append(error_record(error, 'service-log-sanitize'))
        for key, previous in proxy_restore.items():
            if previous is None:
                os.environ.pop(key, None)
            else:
                os.environ[key] = previous
        # Before yield the outer `as` variable is never assigned. Carry every
        # failed cleanup explicitly instead of losing those facts with the context.
        if startup_failure is not None:
            raise OwnedServiceFailure(startup_failure, manifest) from startup_failure


def source_metadata(fixture_path):
    root = Path(__file__).resolve().parent
    files = sorted([*root.glob('app/*.py'), root / 'evaluate_rag.py', root / 'run_rag_baseline.py',
                    *root.glob('requirements*.txt')], key=lambda path: path.relative_to(root).as_posix())
    fingerprints = {path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
    digest = hashlib.sha256(json.dumps(fingerprints, sort_keys=True, separators=(',', ':')).encode('utf-8')).hexdigest()
    def git(*args):
        try:
            result = subprocess.run(['git', *args], cwd=root, capture_output=True, text=True, timeout=10)
            return result.stdout.strip() if result.returncode == 0 else None
        except (OSError, subprocess.TimeoutExpired):
            return None
    status = git('status', '--porcelain', '--untracked-files=no')
    return dict(source_commit=git('rev-parse', 'HEAD'), source_dirty=None if status is None else bool(status),
                source_sha256=digest, source_files_sha256=fingerprints, retrieval_config=RETRIEVAL_CONFIG.copy(),
                fixture_sha256=hashlib.sha256(Path(fixture_path).read_bytes()).hexdigest(),
                environment=dict(python=platform.python_version(), system=platform.system(), release=platform.release(),
                                 machine=platform.machine(), cpu_count=os.cpu_count()))


def run_baseline(*, report_root, fixture_path=None, milvus_uri=None, milvus_token=None, backend='milvus',
                 startup_timeout=600, rounds=3, warmup_rounds=3):
    run_id = time.strftime('%Y%m%dT%H%M%SZ', time.gmtime()) + '-' + uuid.uuid4().hex
    directory = Path(report_root) / run_id
    directory.mkdir(parents=True, exist_ok=False)
    path = Path(fixture_path) if fixture_path is not None else Path(__file__).resolve().parent / 'evals/policy_rag_cases.json'
    report = dict(schema_version=1, status='failed', metadata=dict(run_id=run_id), metrics={}, gates={}, results=[],
                  warmup_results=[], diagnostics=[], errors=[], cleanup_errors=[], cleanup={})
    manifest = None
    redactions = []
    try:
        metadata = source_metadata(path)
        metadata['run_id'] = run_id
        report['metadata'].update(metadata)
        fixture = json.loads(path.read_text(encoding='utf-8'))
        uri = milvus_uri if milvus_uri is not None else os.getenv('RAG_TEST_MILVUS_URI')
        token = milvus_token if milvus_token is not None else os.getenv('RAG_TEST_MILVUS_TOKEN')
        redactions.extend([token, token.split(':', 1)[-1] if token else None])
        with create_owned_service(report_dir=directory, backend=backend, milvus_uri=uri,
                                  milvus_token=token, startup_timeout=startup_timeout) as manifest:
            metadata.update(manifest['metadata'])
            redactions.extend(manifest.get('redactions', []))
            report = run_evaluation(manifest['client'], fixture, directory, expected_backend=backend + '-hybrid',
                                    expected_model=BGE_MODEL, rounds=rounds, warmup_rounds=warmup_rounds, metadata=metadata, redactions=redactions)
    except Exception as error:
        if isinstance(error, OwnedServiceFailure):
            manifest = error.manifest
            report['errors'].append(error_record(error.original, 'owned-runner'))
        else:
            report['errors'].append(error_record(error, 'owned-runner'))
    finally:
        if manifest is not None:
            redactions.extend(manifest.get('redactions', []))
            report['metadata'].update(manifest['metadata'])
            report['cleanup_errors'].extend(manifest['cleanup_errors'])
            report['cleanup'].update(manifest['cleanup'])
        if not report['metrics']:
            report['metrics'] = calculate_metrics(report['results'])
        if not report['gates']:
            report['gates'] = evaluate_gates(report['metrics'])
        write_report(report, directory, redactions=redactions)
    print(json.dumps(dict(report_directory=directory.name, status=report['status'], metrics=report['metrics']), ensure_ascii=False))
    return exit_code(report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--disposable-test', action='store_true', help='Create only an owned temporary service and collection')
    parser.add_argument('--backend', choices=('milvus', 'memory'), default='milvus')
    parser.add_argument('--report-dir', type=Path, default=Path(__file__).resolve().parent / 'evals/reports')
    parser.add_argument('--fixture', type=Path, default=None)
    parser.add_argument('--startup-timeout', type=float, default=600)
    args = parser.parse_args()
    if not args.disposable_test:
        parser.error('--disposable-test is required; evaluation never writes to an existing HTTP service')
    return run_baseline(report_root=args.report_dir, fixture_path=args.fixture, backend=args.backend,
                        startup_timeout=args.startup_timeout)


if __name__ == '__main__':
    raise SystemExit(main())
