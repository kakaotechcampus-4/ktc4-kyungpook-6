"""State machine owns completion and values; the LLM only interprets a turn."""
import json
from dataclasses import dataclass
from pathlib import Path
from .models import CallRequest, Confirmation, Interpretation

@dataclass
class Reply:
    text: str
    terminal: bool = False
    reason: str | None = None

class Dialogue:
    def __init__(self, request: CallRequest, persona_file: Path):
        self.request = request
        self.policy = json.loads(persona_file.read_text(encoding='utf-8'))
        required = {'greeting','identityRetry','expected','current','askValue','readback',
                    'ack','retry','goodbye','stopped','wrongTarget','timeout','error'}
        if not required <= self.policy['templates'].keys():
            raise ValueError('agent.json에 필수 대화 템플릿이 없습니다.')
        self.items = []
        for q in request.questions:
            for field in ([q] if isinstance(q, Confirmation) else q.fields):
                item = field.model_dump()
                item.update(questionId=q.id, type=q.type)
                self.items.append(item)
        self.index = 0
        self.stage = 'IDENTITY'
        self.answers = []
        self.candidate = None
        self.identity_retries = 0
        self.history = []
        self.last_text = self.template('greeting')

    @property
    def complete(self):
        return self.index == len(self.items)

    @property
    def item(self):
        return self.items[self.index] if not self.complete else None

    def template(self, name, **kwargs):
        return self.policy['templates'][name].format(**self.request.target.model_dump(), **kwargs)

    def spoken(self, value):
        if isinstance(value, bool):
            labels = self.policy.get('booleanLabels', {})
            labels = labels.get(self.item['field'], labels.get('default', {'true':'예','false':'아니요'}))
            return labels[str(value).lower()]
        return value

    def question(self):
        item = self.item
        if self.stage == 'IDENTITY':
            return self.template('identityRetry')
        values = dict(naturalField=item['naturalField'], currentValue=self.spoken(item['currentValue']))
        if self.stage == 'EXPECTED':
            return self.template('expected', **values, expectedValue=self.spoken(item['expectedValue']))
        if self.stage == 'CURRENT':
            return self.template('current', **values)
        if self.stage == 'READBACK':
            return self.template('readback', naturalField=item['naturalField'], value=self.spoken(self.candidate))
        return self.template('askValue', naturalField=item['naturalField'])

    def state(self):
        return {'stage': self.stage, 'target': self.request.target.model_dump(exclude={'phoneNumber'}),
                'activeField': self.item if not self.complete else self.items[-1], 'candidate': self.candidate,
                'lastAssistant': self.last_text, 'history': self.history[-10:]}

    def _start_item(self):
        self.stage = 'EXPECTED' if self.item['type'] == 'VALUE_CONFIRMATION' else 'CURRENT'
        self.candidate = None
        return self.question()

    def _commit(self, value):
        item = self.item
        ack = self.template('ack', naturalField=item['naturalField'], value=self.spoken(value))
        self.answers.append({'questionId': item['questionId'], 'field': item['field'], 'value': value})
        self.index += 1
        if self.complete:
            return Reply(ack + ' ' + self.template('goodbye'), True)
        return Reply(ack + ' ' + self._start_item())

    def apply(self, text: str, decision: Interpretation) -> Reply:
        self.history.extend([{'role':'assistant','content':self.last_text}, {'role':'user','content':text}])
        result = self._apply(text, decision)
        self.last_text = result.text
        return result

    def _apply(self, text, decision):
        if decision.stop:
            return Reply(self.template('stopped'), True, None if self.complete else 'USER_REQUESTED_STOP')
        if self.complete:
            # A correction during the final readback reopens the last field.
            if decision.kind == 'NO' or (decision.kind in ('CURRENT','EXPECTED','VALUE')
                    and decision.evidence.strip() and decision.evidence in text):
                previous = self.answers.pop()
                self.index -= 1
                self.candidate = previous['value']
                self.stage = 'READBACK'
            else:
                return Reply(self.template('goodbye'), True)
        if self.stage == 'IDENTITY':
            if decision.identity == 'NO':
                return Reply(self.template('wrongTarget'), True, 'WRONG_TARGET')
            if decision.identity == 'YES':
                return Reply(self._start_item())
            self.identity_retries += 1
            if self.identity_retries >= 3:
                return Reply(self.template('wrongTarget'), True, 'IDENTITY_UNCONFIRMED')
            return Reply((decision.knowledgeReply + ' ' if decision.knowledgeReply else '') + self.question())

        item = self.item
        kind = decision.kind
        if kind == 'YES' and self.stage in ('EXPECTED','CURRENT','READBACK'):
            value = self.candidate if self.stage == 'READBACK' else item['expectedValue' if self.stage == 'EXPECTED' else 'currentValue']
            return self._commit(value)
        if kind == 'NO':
            if self.stage == 'EXPECTED':
                self.stage = 'CURRENT'
            elif self.stage == 'CURRENT' and isinstance(item['currentValue'], bool):
                self.candidate = not item['currentValue']
                self.stage = 'READBACK'
            else:
                self.stage = 'VALUE'
                self.candidate = None
            return Reply(self.question())
        # Explicit answers need an actual quoted evidence span; bare 'no' does not mean the old name.
        if kind in ('CURRENT','EXPECTED','VALUE') and decision.evidence.strip() and decision.evidence in text:
            value = item['currentValue'] if kind == 'CURRENT' else item.get('expectedValue') if kind == 'EXPECTED' else decision.value
            if type(value) is type(item['currentValue']):
                if kind in ('CURRENT','EXPECTED'):
                    return self._commit(value)
                self.candidate = value
                self.stage = 'READBACK'
                return Reply(self.question())
        prefix = decision.knowledgeReply.strip()
        return Reply((prefix + ' ' if prefix else '') + self.template('retry', question=self.question()))
