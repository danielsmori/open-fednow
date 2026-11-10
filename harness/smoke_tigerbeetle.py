#!/usr/bin/env python3
"""Run a published case against a disposable local TigerBeetle-backed target."""
import argparse
import hashlib
import importlib.metadata
import json
import subprocess
import sys
import tempfile
import time
import uuid
from pathlib import Path

from smoke_profile import free_port, ready, stop

ROOT = Path(__file__).resolve().parents[1]


def ledger_ready(address, process):
    from tigerbeetle_ledger import Ledger
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline and process.poll() is None:
        try:
            ledger = Ledger(address)
            ledger.client.lookup_accounts([1])
            ledger.close()
            return True
        except Exception:
            time.sleep(0.2)
    return False


def contention(address):
    from tigerbeetle_ledger import Ledger
    ledger = Ledger(address)
    try:
        account = 'SYN-CONTENTION-' + uuid.uuid4().hex
        winner = 'SYN-WINNER-' + uuid.uuid4().hex
        loser = 'SYN-LOSER-' + uuid.uuid4().hex
        ledger.seed(account, 100000)
        first = ledger.reserve(winner, account, 70000)
        before_second = ledger.account(account)
        second = ledger.reserve(loser, account, 70000)
        after_second = ledger.account(account)
        loser_effects = ledger.effects(loser, account, 70000)
        if not (first and before_second == (100000, 70000)
                and second is False and after_second == (100000, 70000)
                and loser_effects == []):
            raise AssertionError('pending contention invariant failed')
        ledger.finalize(winner, account, 70000, 'SETTLED')
        final = ledger.account(account)
        winner_effects = ledger.effects(winner, account, 70000)
        if final != (30000, 0) or winner_effects != ['HOLD', 'POST']:
            raise AssertionError('contention final ledger invariant failed')
        return {'result': 'PASS', 'first_hold_pending_at_second_reservation': True,
                'second_reservation_refused_by_ledger': True,
                'before_second': {'ledger_minor': before_second[0], 'held_minor': before_second[1]},
                'after_second': {'ledger_minor': after_second[0], 'held_minor': after_second[1]},
                'loser_effect_types': loser_effects,
                'final': {'ledger_minor': final[0], 'held_minor': final[1]},
                'winner_effect_types': winner_effects}
    finally:
        ledger.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--binary', required=True, type=Path)
    parser.add_argument('--case', default='S16', choices=('S01', 'S16'))
    parser.add_argument('--output', default='target/tigerbeetle/s16-report.json', type=Path)
    args = parser.parse_args(argv)
    binary = args.binary.resolve()
    if not binary.is_file():
        parser.error('pinned TigerBeetle binary is missing')
    version = subprocess.check_output([str(binary), 'version'], text=True).strip()
    if '0.17.9' not in version or importlib.metadata.version('tigerbeetle') != '0.17.9':
        parser.error('server and SDK must both be pinned to 0.17.9')
    from tigerbeetle_ledger import Ledger  # dependency check before starting children
    del Ledger
    with tempfile.TemporaryDirectory(prefix='openfednow-tb-') as directory:
        state = Path(directory)
        ports = set()
        while len(ports) < 3:
            ports.add(free_port())
        tb_port, rail_port, target_port = ports
        address = f'127.0.0.1:{tb_port}'
        rail_url = f'http://127.0.0.1:{rail_port}'
        target_url = f'http://127.0.0.1:{target_port}'
        data = state / '0_0.tigerbeetle'
        subprocess.run([str(binary), 'format', '--cluster=0', '--replica=0',
            '--replica-count=1', '--development', str(data)], check=True,
            stdout=subprocess.DEVNULL)
        processes = []
        try:
            with (state / 'tigerbeetle.log').open('wb') as log:
                server = subprocess.Popen([str(binary), 'start', f'--addresses={address}',
                    '--development', str(data)], stdout=log, stderr=subprocess.STDOUT)
                processes.append(server)
                if not ledger_ready(address, server):
                    raise RuntimeError('TigerBeetle did not become ready')
            with (state / 'rail.log').open('wb') as log:
                rail = subprocess.Popen([sys.executable, str(ROOT / 'harness/rail_simulator.py'),
                    '--port', str(rail_port), '--db', str(state / 'rail.sqlite')],
                    cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                processes.append(rail)
                if not ready(rail_url + '/health', rail):
                    raise RuntimeError('synthetic rail did not become ready')
            with (state / 'target.log').open('wb') as log:
                target = subprocess.Popen([sys.executable, str(ROOT / 'harness/reference_target.py'),
                    '--port', str(target_port), '--db', str(state / 'reference.sqlite'),
                    '--rail-url', rail_url, '--tigerbeetle-address', address], cwd=ROOT,
                    stdout=log, stderr=subprocess.STDOUT)
                processes.append(target)
                if not ready(target_url + '/health', target):
                    raise RuntimeError('TigerBeetle-backed target did not become ready')
            args.output.parent.mkdir(parents=True, exist_ok=True)
            profile_path = args.output.resolve()
            result = subprocess.run([sys.executable, str(ROOT / 'harness/portable_profile.py'),
                '--target-url', target_url, '--rail-url', rail_url, '--case', args.case,
                '--target-revision', 'reference_target.py:sha256:' + hashlib.sha256(
                    (ROOT / 'harness/reference_target.py').read_bytes()).hexdigest()[:16],
                '--environment-type', 'synthetic-local', '--output', str(profile_path)],
                cwd=ROOT, check=False)
            if args.case == 'S16':
                try:
                    contention_result = contention(address)
                except AssertionError:
                    contention_result = {'result': 'FAIL', 'reason': 'ledger_contention_invariant'}
                except Exception:
                    contention_result = {'result': 'BLOCKED', 'reason': 'ledger_observation_unavailable'}
            else:
                contention_result = {'result': 'NOT_RUN', 'reason': 'contention check belongs to S16'}
            extra = {'experiment_schema': 'tigerbeetle-s16-v1',
                'server_version': version, 'sdk_version': importlib.metadata.version('tigerbeetle'),
                'ledger_module_sha256': hashlib.sha256((ROOT / 'harness/tigerbeetle_ledger.py').read_bytes()).hexdigest(),
                'contention': contention_result}
            extra_path = profile_path.with_name(profile_path.stem + '-contention.json')
            extra_path.write_text(json.dumps(extra, indent=2) + '\n')
            print(f'Contention: {contention_result["result"]}; report: {extra_path}')
            return 0 if result.returncode == 0 and contention_result['result'] in ('PASS', 'NOT_RUN') else 1
        finally:
            for process in reversed(processes):
                stop(process)


if __name__ == '__main__':
    raise SystemExit(main())
