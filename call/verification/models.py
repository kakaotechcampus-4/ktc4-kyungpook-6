"""Public HTTP contract. No implicit string/boolean coercion."""
import re
from typing import Annotated, Literal
from pydantic import BaseModel, ConfigDict, Field, StrictBool, StrictStr, field_validator, model_validator

Value = StrictBool | Annotated[StrictStr, Field(min_length=1, max_length=2000)]
Name = Annotated[str, Field(strict=True, min_length=1, max_length=120)]

class Model(BaseModel):
    model_config = ConfigDict(extra='forbid')

class Target(Model):
    storeLocation: Annotated[str, Field(min_length=1, max_length=2000)]
    storeName: Name
    phoneNumber: str

    @field_validator('phoneNumber')
    @classmethod
    def phone(cls, value):
        value = re.sub(r'[- ()]', '', value)
        if not re.fullmatch(r'\+?[0-9]{7,15}', value):
            raise ValueError('전화번호는 7~15자리 숫자와 선택적인 +만 사용할 수 있습니다.')
        return value

class FieldInfo(Model):
    field: Name
    naturalField: Name
    currentValue: Value

class Confirmation(FieldInfo):
    id: Name
    type: Literal['VALUE_CONFIRMATION']
    expectedValue: Value

    @model_validator(mode='after')
    def same_type(self):
        if type(self.currentValue) is not type(self.expectedValue):
            raise ValueError('currentValue와 expectedValue의 타입이 같아야 합니다.')
        return self

class Detection(Model):
    id: Name
    type: Literal['CHANGE_DETECTION']
    fields: Annotated[list[FieldInfo], Field(min_length=1, max_length=30)]

    @model_validator(mode='after')
    def unique_fields(self):
        if len({item.field for item in self.fields}) != len(self.fields):
            raise ValueError('한 CHANGE_DETECTION 안의 field는 중복할 수 없습니다.')
        return self

Question = Annotated[Confirmation | Detection, Field(discriminator='type')]

class CallRequest(Model):
    target: Target
    questions: Annotated[list[Question], Field(min_length=1, max_length=30)]

    @model_validator(mode='after')
    def unique_questions(self):
        if len({q.id for q in self.questions}) != len(self.questions):
            raise ValueError('질문 id는 중복할 수 없습니다.')
        if sum(len(q.fields) if isinstance(q, Detection) else 1 for q in self.questions) > 60:
            raise ValueError('한 통화는 최대 60개 필드를 확인할 수 있습니다.')
        return self

class Created(Model):
    verificationId: str

class Answer(Model):
    questionId: str
    field: str
    value: Value

class CallResult(Model):
    verificationId: str
    status: Literal['PENDING', 'IN_PROGRESS', 'COMPLETED', 'FAILED']
    answers: list[Answer]
    createdAt: str
    startedAt: str | None = None
    connectedAt: str | None = None
    endedAt: str | None = None
    failureReason: str | None = None

class Interpretation(Model):
    identity: Literal['YES', 'NO', 'UNKNOWN'] = 'UNKNOWN'
    control: Literal['STOP', 'WAIT', 'CONTINUE'] = 'CONTINUE'
    kind: Literal['YES', 'NO', 'CURRENT', 'EXPECTED', 'VALUE', 'UNCLEAR'] = 'UNCLEAR'
    value: Value | None = None
    evidence: str = ''

class ValueExtraction(Model):
    value: Value | None
    evidence: Annotated[str, Field(max_length=2000)]

class CustomReply(Model):
    reply: Annotated[StrictStr, Field(min_length=1, max_length=300)]

    @model_validator(mode='after')
    def valid_reply(self):
        if not self.reply.strip() or '\n' in self.reply:
            raise ValueError('CUSTOM 답변은 한 줄의 문장이 필요합니다.')
        return self
