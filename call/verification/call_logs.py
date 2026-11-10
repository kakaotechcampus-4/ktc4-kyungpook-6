"""Public conversation log projected from the call's live JSONL event log."""

import json
from pathlib import Path


def read_call_logs(folder: Path, after: int = 0):
    logs = []
    for path in sorted(folder.glob('call-*.jsonl')):
        with path.open(encoding='utf-8') as stream:
            for line in stream:
                # A concurrent writer may not have finished the final line yet.
                if not line.endswith('\n'):
                    continue
                try:
                    event = json.loads(line)
                    stage = event['stage']
                    if stage in ('assistant.response', 'user.transcript'):
                        entry = {
                            'timestamp': event['timestamp'],
                            'turn': event['turn'],
                            'type': 'ASSISTANT' if stage == 'assistant.response' else 'USER',
                            'text': event['text'],
                        }
                    elif stage == 'jev' and event.get('status') == 'ok':
                        result = event['output']
                        entry = {
                            'timestamp': event['timestamp'],
                            'turn': event['turn'],
                            'type': 'JEV',
                            'verdict': result['verdict'],
                            'answer': result['answer'],
                            'answerConfidence': result['answer_confidence'],
                            'control': result['control'],
                            'controlConfidence': result['control_confidence'],
                        }
                    else:
                        continue
                except (KeyError, TypeError, ValueError):
                    # Only fully formed public events are returned.
                    continue
                entry['sequence'] = len(logs) + 1
                logs.append(entry)
    return logs[after:], len(logs)
