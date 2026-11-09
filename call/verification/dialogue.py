"""State machine owns completion and values; the LLM only interprets a turn."""
import json
import random
from string import Formatter
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
                    'ack','retry','goodbye','stopped','wrongTarget','timeout','error',
                    'llmWait','waitAcknowledgement'}
        if not required <= self.policy['templates'].keys():
            raise ValueError('agent.json에 필수 대화 템플릿이 없습니다.')
        target_fields = set(self.request.target.model_dump())
        allowed_fields = target_fields | {'naturalField','currentValue','expectedValue','value','question'}
        for name, value in self.policy['templates'].items():
            variants = [value] if isinstance(value, str) else value
            if (not isinstance(variants, list) or not variants or
                    any(not isinstance(text, str) or not text.strip() for text in variants)):
                raise ValueError(f'agent.json 템플릿 형식 오류: {name}')
            for text in variants:
                fields = {field for _, field, _, _ in Formatter().parse(text) if field is not None}
                if (not fields <= allowed_fields or
                        (name in ('llmWait','waitAcknowledgement') and not fields <= target_fields)):
                    raise ValueError(f'agent.json 템플릿 변수 오류: {name}')
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
        self.stopped = False
        self.stop_reason = None
        self.history = []
        self.last_text = self.template('greeting')

    @property
    def complete(self):
        return self.index == len(self.items)

    @property
    def item(self):
        return self.items[self.index] if not self.complete else None

    def template_variants(self, name, **kwargs):
        return [text.format(**self.request.target.model_dump(), **kwargs)
                for text in self.raw_variants(name)]

    def raw_variants(self, name):
        value = self.policy['templates'][name]
        return [value] if isinstance(value, str) else value

    def template(self, name, **kwargs):
        return random.choice(self.template_variants(name, **kwargs))

    def is_static_template(self, name):
        return all(field is None for text in self.raw_variants(name)
                   for _, field, _, _ in Formatter().parse(text))

    def static_templates(self):
        return {f'{name}[{index}]': text for name in self.policy['templates']
                for index, text in enumerate(self.raw_variants(name))
                if all(field is None for _, field, _, _ in Formatter().parse(text))}

    def prewarm_texts(self):
        target_fields = self.request.target.model_dump().keys()
        return [text.format(**self.request.target.model_dump())
                for name in self.policy['templates']
                for text in self.raw_variants(name)
                if all(field is None or field in target_fields
                       for _, field, _, _ in Formatter().parse(text))]

    def spoken(self, value):
        if isinstance(value, bool):
            labels = self.policy.get('booleanLabels', {})
            labels = labels.get(self.item['field'], labels.get('default', {'true':'예','false':'아니요'}))
            return labels[str(value).lower()]
        return value

    def question_spec(self):
        item = self.item
        if self.stage == 'IDENTITY':
            return 'identityRetry', {}
        values = dict(naturalField=item['naturalField'], currentValue=self.spoken(item['currentValue']))
        if self.stage == 'EXPECTED':
            return 'expected', dict(values, expectedValue=self.spoken(item['expectedValue']))
        if self.stage == 'CURRENT':
            return 'current', values
        if self.stage == 'READBACK':
            return 'readback', dict(naturalField=item['naturalField'], value=self.spoken(self.candidate))
        return 'askValue', {'naturalField':item['naturalField']}

    def question(self):
        name, values = self.question_spec()
        return self.template(name, **values)

    def question_variants(self):
        name, values = self.question_spec()
        return self.template_variants(name, **values)

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
        if self.stopped:
            return Reply(self.template('stopped'), True, self.stop_reason)
        self.history.extend([{'role':'assistant','content':self.last_text}, {'role':'user','content':text}])
        result = self._apply(text, decision)
        self.last_text = result.text
        return result

    def repeat(self, text: str, reply: str) -> None:
        self.history.extend([{'role':'assistant','content':self.last_text},
                             {'role':'user','content':text}])
        self.last_text = reply

    def _apply(self, text, decision):
        if decision.control == 'STOP':
            self.stopped = True
            self.stop_reason = None if self.complete else 'USER_REQUESTED_STOP'
            return Reply(self.template('stopped'), True, self.stop_reason)
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
            if not decision.knowledgeReply.strip():
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
