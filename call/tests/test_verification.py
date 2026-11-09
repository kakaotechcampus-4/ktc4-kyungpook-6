import copy
import json
import sqlite3
import tempfile
import threading
import time
import unittest
import wave
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from unittest.mock import Mock

import httpx
from fastapi.testclient import TestClient
from pydantic import ValidationError
from verification.api import create_app
from verification.config import Settings
from verification.dialogue import Dialogue
from verification.models import CallRequest, Interpretation
from verification.providers import Providers, with_tts_pauses
from verification.store import Store, QueueFull
from verification.worker import Worker

ROOT = Path(__file__).resolve().parents[1]
PAYLOAD = json.loads((ROOT/'examples/request.json').read_text())

def request(single=True):
    body = copy.deepcopy(PAYLOAD)
    if single:
        body['questions'] = body['questions'][:1]
    return CallRequest.model_validate(body)

def settings(folder):
    return Settings(data_dir=folder, persona_file=ROOT/'agent.json',modem_lock_file=folder/'modem.lock',
        api_key='api-test',stt_api_key='stt-test',llm_api_key='llm-test',elevenlabs_api_key='tts-test',
        jev_api_key='jev-test',elevenlabs_voice_id='voice-test',
        stt_url='https://stt.example/v1/audio/transcriptions',
        llm_base_url='https://llm.example/v1',jev_url='https://jev.example/v1/decisions')

def wav(path,rate=16000):
    with wave.open(str(path),'wb') as f:
        f.setnchannels(1); f.setsampwidth(2); f.setframerate(rate); f.writeframes(bytes(rate*2))
    return path

class ContractTests(unittest.TestCase):
    def test_phone_normalization(self):
        self.assertEqual(request().target.phoneNumber,'01012345678')

    def test_phone_at_injection_rejected(self):
        for phone in ('01012345678;AT+CHUP','123\r\nAT','abc','１２３４５６７８９'):
            body=copy.deepcopy(PAYLOAD); body['target']['phoneNumber']=phone
            with self.assertRaises(ValidationError): CallRequest.model_validate(body)

    def test_duplicate_ids_empty_and_mismatched_types(self):
        for mutate in (lambda p:p.update(questions=[]),
                       lambda p:p['questions'].append(p['questions'][0]),
                       lambda p:p['questions'][0].update(expectedValue=True),
                       lambda p:p['questions'][0].update(currentValue=1,expectedValue=2)):
            body=copy.deepcopy(PAYLOAD); mutate(body)
            with self.assertRaises(ValidationError): CallRequest.model_validate(body)

    def test_duplicate_detection_field_rejected(self):
        body=copy.deepcopy(PAYLOAD)
        body['questions'][1]['fields'].append(body['questions'][1]['fields'][0])
        with self.assertRaises(ValidationError): CallRequest.model_validate(body)

class DialogueTests(unittest.TestCase):
    def dialogue(self, req=None):
        return Dialogue(req or request(),ROOT/'agent.json')

    def identified(self, req=None):
        d=self.dialogue(req)
        d.apply('네 관계자입니다',Interpretation(identity='YES'))
        return d

    def test_identity_is_not_a_field_answer(self):
        d=self.identified()
        self.assertFalse(d.answers)
        self.assertEqual(d.stage,'EXPECTED')

    def test_expected_confirmation_and_goodbye(self):
        d=self.identified()
        reply=d.apply('네',Interpretation(kind='YES'))
        self.assertTrue(reply.terminal)
        self.assertIsNone(reply.reason)
        self.assertEqual(d.answers,[{'questionId':'q1','field':'storeName','value':'새봄식당'}])

    def test_denial_does_not_assume_old_value(self):
        d=self.identified()
        d.apply('아니요',Interpretation(kind='NO'))
        self.assertEqual(d.stage,'CURRENT')
        self.assertFalse(d.answers)
        d.apply('그대로예요',Interpretation(kind='YES'))
        self.assertEqual(d.answers[0]['value'],'땡땡식당')

    def test_new_value_requires_readback(self):
        d=self.identified()
        d.apply('다온식당이에요',Interpretation(kind='VALUE',value='다온식당',evidence='다온식당'))
        self.assertEqual(d.stage,'READBACK')
        self.assertFalse(d.answers)
        d.apply('아니요',Interpretation(kind='NO'))
        self.assertEqual(d.stage,'VALUE')
        d.apply('다온식당입니다',Interpretation(kind='VALUE',value='다온식당',evidence='다온식당'))
        d.apply('네',Interpretation(kind='YES'))
        self.assertEqual(d.answers[0]['value'],'다온식당')

    def test_missing_or_fabricated_evidence_not_saved(self):
        for evidence in ('','없는 말'):
            d=self.identified()
            d.apply('모르겠어요',Interpretation(kind='EXPECTED',evidence=evidence))
            self.assertFalse(d.answers)

    def test_boolean_is_preserved_and_false_is_not_missing(self):
        body=request().model_dump()
        body['questions'][0].update(field='isOpen',naturalField='영업 여부',currentValue=True,expectedValue=False)
        d=self.identified(CallRequest.model_validate(body))
        d.apply('네',Interpretation(kind='YES'))
        self.assertIs(d.answers[0]['value'],False)

    def test_boolean_denial_of_current_requires_readback(self):
        body=request().model_dump()
        body['questions']=[{'id':'q1','type':'CHANGE_DETECTION','fields':[{'field':'isOpen','naturalField':'영업 여부','currentValue':True}]}]
        d=self.identified(CallRequest.model_validate(body))
        d.apply('아니요',Interpretation(kind='NO'))
        self.assertEqual(d.stage,'READBACK'); self.assertFalse(d.answers)
        d.apply('네',Interpretation(kind='YES'))
        self.assertIs(d.answers[0]['value'],False)

    def test_wrong_type_new_value_rejected(self):
        d=self.identified()
        d.apply('네',Interpretation(kind='VALUE',value=True,evidence='네'))
        self.assertFalse(d.answers); self.assertEqual(d.stage,'EXPECTED')

    def test_duplicates_across_questions_are_preserved(self):
        d=self.identified(request(False))
        for _ in range(4):
            result=d.apply('네',Interpretation(kind='YES'))
        self.assertTrue(result.terminal)
        self.assertEqual([a['questionId'] for a in d.answers],['q1','q2','q2','q2'])
        self.assertEqual([a['value'] for a in d.answers[:2]],['새봄식당','땡땡식당'])

    def test_stop_keeps_partial_answers_and_fails(self):
        d=self.identified(request(False))
        d.apply('네',Interpretation(kind='YES'))
        result=d.apply('끊어주세요',Interpretation(stop=True))
        self.assertEqual(result.reason,'USER_REQUESTED_STOP'); self.assertEqual(len(d.answers),1)

    def test_wrong_identity_and_repeated_unknown(self):
        d=self.dialogue()
        self.assertEqual(d.apply('잘못 거셨어요',Interpretation(identity='NO')).reason,'WRONG_TARGET')
        d=self.dialogue()
        for _ in range(3): result=d.apply('누구세요?',Interpretation())
        self.assertEqual(result.reason,'IDENTITY_UNCONFIRMED')

class StoreTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.folder=Path(self.tmp.name); self.store=Store(self.folder/'calls.db')

    def test_fifo_durable_and_recovery_does_not_redial(self):
        a=self.store.enqueue(request()); b=self.store.enqueue(request())
        self.assertEqual(self.store.claim()[0],a)
        self.store.started(a)
        self.store.save_answers(a,[{'questionId':'q1','field':'name','value':'partial'}])
        reopened=Store(self.store.path); reopened.recover()
        self.assertEqual(reopened.get(a)['status'],'FAILED')
        self.assertEqual(reopened.get(a)['failureReason'],'SERVER_RESTARTED')
        self.assertEqual(reopened.claim()[0],b)
        self.assertIsNone(reopened.claim())
        self.assertEqual(len(reopened.get(a)['answers']),1)

    def test_preparation_crash_is_failed_and_capacity_atomic(self):
        store=Store(self.folder/'small.db',capacity=1)
        a=store.enqueue(request())
        with self.assertRaises(QueueFull): store.enqueue(request())
        store.claim(); store.recover()
        self.assertEqual(store.get(a)['status'],'FAILED')
        store.enqueue(request())

    def test_concurrent_enqueue_and_claim_once(self):
        with ThreadPoolExecutor(max_workers=8) as pool:
            ids=list(pool.map(lambda _:self.store.enqueue(request()),range(20)))
            claimed=list(pool.map(lambda _:self.store.claim()[0],range(20)))
        self.assertEqual(set(ids),set(claimed))
        self.assertIsNone(self.store.claim())

    def test_worker_serializes_modem_and_survives_call_error(self):
        ids=[self.store.enqueue(request()) for _ in range(3)]
        seen=[]; active=0; peak=0
        def runner(cid,req,stop):
            nonlocal active,peak
            active+=1; peak=max(peak,active); seen.append(cid)
            time.sleep(.015); active-=1
            if len(seen)==1: raise RuntimeError('mock error')
            self.store.finish(cid)
        w=Worker(self.store,runner,self.folder/'modem.lock'); w.start()
        try:
            deadline=time.monotonic()+2
            while self.store.get(ids[-1])['status']=='PENDING' and time.monotonic()<deadline: time.sleep(.01)
        finally: w.stop()
        self.assertEqual(seen,ids); self.assertEqual(peak,1)
        self.assertEqual(self.store.get(ids[0])['status'],'FAILED')
        self.assertEqual(self.store.get(ids[-1])['status'],'COMPLETED')

    def test_worker_retries_transient_claim_and_finish_errors(self):
        cid=self.store.enqueue(request())
        claim=self.store.claim
        finish=self.store.finish
        attempts={'claim':0,'finish':0,'run':0}
        def flaky_claim():
            attempts['claim']+=1
            if attempts['claim']==1: raise sqlite3.OperationalError('busy')
            return claim()
        def flaky_finish(call_id,reason=None):
            attempts['finish']+=1
            if attempts['finish']==1: raise sqlite3.OperationalError('busy')
            return finish(call_id,reason)
        def runner(call_id,req,stop):
            attempts['run']+=1
            raise RuntimeError('mock failure')
        self.store.claim=flaky_claim
        self.store.finish=flaky_finish
        w=Worker(self.store,runner,self.folder/'modem.lock'); w.start()
        try:
            deadline=time.monotonic()+3
            while self.store.get(cid)['status']!='FAILED' and time.monotonic()<deadline:
                time.sleep(.01)
            self.assertTrue(w.thread.is_alive())
            self.assertEqual(self.store.get(cid)['failureReason'],'PROCESSING_ERROR')
            self.assertEqual(attempts['run'],1)
            self.assertGreaterEqual(attempts['claim'],2)
            self.assertGreaterEqual(attempts['finish'],2)
        finally: w.stop()

    def test_second_process_worker_lock_rejected(self):
        a=Worker(self.store,Mock(),self.folder/'modem.lock'); a.start()
        try:
            b=Worker(Store(self.folder/'other.db'),Mock(),self.folder/'modem.lock')
            with self.assertRaises(RuntimeError): b.start()
        finally: a.stop()

    def test_worker_open_failure_preserves_cause_and_closes_first_lock(self):
        w=Worker(self.store,Mock(),self.folder/'modem.lock')
        original=Path.open
        def open_file(path,*args,**kwargs):
            if path.name=='calls.worker.lock': raise PermissionError('denied')
            return original(path,*args,**kwargs)
        from unittest.mock import patch
        with patch.object(Path,'open',open_file):
            with self.assertRaisesRegex(RuntimeError,'시작에 실패') as error:
                w.start()
        self.assertIsInstance(error.exception.__cause__,PermissionError)
        self.assertIsNone(w.lock)
        self.assertIsNone(w.db_lock)

class ProviderTests(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(); self.addCleanup(self.tmp.cleanup)
        self.folder=Path(self.tmp.name); self.s=settings(self.folder)

    def provider(self,handler):
        client=httpx.Client(transport=httpx.MockTransport(handler)); self.addCleanup(client.close)
        return Providers(self.s,client)

    def test_jev_contract_and_classification(self):
        def handler(req):
            body=json.loads(req.content)
            self.assertEqual(str(req.url),self.s.jev_url)
            self.assertEqual(req.headers['authorization'],'Bearer jev-test')
            self.assertEqual(body['model'],'typesafe/jev-latest')
            self.assertEqual(body['questions']['interrupt']['type'],'noul')
            self.assertEqual(body['questions']['irrelevant']['type'],'noul')
            self.assertTrue(body['state']['overlapping'])
            return httpx.Response(200,json={'answers':{'irrelevant':{'noul':.01},
                                                     'interrupt':{'noul':.99},'backchannel':{'noul':.01}}})
        self.assertEqual(self.provider(handler).classify_overlap('아니오','안내',{}),'INTERRUPT')

    def test_jev_backchannel_uncertain_and_invalid(self):
        for a,b,want in ((.01,.99,'BACKCHANNEL'),(.5,.5,'INTERRUPT')):
            p=self.provider(lambda req:httpx.Response(200,json={'answers':{'irrelevant':{'noul':.01},
                'interrupt':{'noul':a},'backchannel':{'noul':b}}}))
            self.assertEqual(p.classify_overlap('네네','안내',{}),want)
        p=self.provider(lambda req:httpx.Response(200,json={'answers':{'irrelevant':{'noul':.01},
            'interrupt':{'noul':'true'},'backchannel':{'noul':1}}}))
        with self.assertRaises(ValueError): p.classify_overlap('네','안내',{})

    def test_jev_ignores_only_clearly_irrelevant_nonoverlap(self):
        def handler(req):
            body=json.loads(req.content)
            self.assertEqual(set(body['questions']),{'irrelevant'})
            self.assertFalse(body['state']['overlapping'])
            return httpx.Response(200,json={'answers':{'irrelevant':{'noul':.95}}})
        result=self.provider(handler).classify_utterance('감사합니다','관계자분 맞으세요?',{})
        self.assertEqual(result['verdict'],'IGNORE')
        self.assertEqual(result['scores']['irrelevant'],.95)
        uncertain=self.provider(lambda req:httpx.Response(200,json={
            'answers':{'irrelevant':{'noul':.5}}})).classify_utterance('네','관계자분 맞으세요?',{})
        self.assertEqual(uncertain['verdict'],'PROCESS')

    def test_stt_rejects_eight_khz_and_sends_sixteen_khz(self):
        seen=[]
        p=self.provider(lambda req:(seen.append(req) or httpx.Response(200,json={'text':'안녕하세요'})))
        with self.assertRaises(ValueError): p.transcribe(wav(self.folder/'bad.wav',8000))
        self.assertFalse(seen)
        self.assertEqual(p.transcribe(wav(self.folder/'ok.wav')),'안녕하세요')
        self.assertIn(b'whisper-large-v3',seen[0].content)

    def test_tts_model_voice_and_cache(self):
        seen=[]
        p=self.provider(lambda req:(seen.append(req) or httpx.Response(200,content=b'audio')))
        a=p.synthesize('안녕하세요. 무엇을 도와드릴까요?',self.folder)
        b=p.synthesize('안녕하세요. 무엇을 도와드릴까요?',self.folder)
        self.assertEqual(a,b); self.assertEqual(len(seen),1)
        body=json.loads(seen[0].content)
        self.assertEqual(body['model_id'],'eleven_multilingual_v2')
        self.assertEqual(body['voice_settings']['speed'],0.98)
        self.assertEqual(body['text'],'안녕하세요. <break time="0.2s" /> 무엇을 도와드릴까요?')
        self.assertEqual(seen[0].url.path,'/v1/text-to-speech/voice-test')
        p.settings.elevenlabs_voice_id='another-voice'
        c=p.synthesize('안녕하세요. 무엇을 도와드릴까요?',self.folder)
        self.assertNotEqual(a,c); self.assertEqual(len(seen),2)
        self.assertEqual(seen[1].url.path,'/v1/text-to-speech/another-voice')

    def test_tts_pause_rule_skips_decimals_and_existing_breaks(self):
        tag='<break time="0.2s" />'
        self.assertEqual(with_tts_pauses('0.98입니다.다음은? 네! 끝.'),
                         f'0.98입니다. {tag} 다음은? {tag} 네! {tag} 끝.')
        self.assertEqual(with_tts_pauses(f'첫 문장. {tag} 다음 문장.'),
                         f'첫 문장. {tag} 다음 문장.')
        self.assertEqual(with_tts_pauses('정말?! 네... 다음입니다.'),
                         f'정말?! {tag} 네... {tag} 다음입니다.')

    def test_llm_schema_and_no_unvalidated_output(self):
        p=self.provider(lambda req:httpx.Response(200,json={'choices':[{'message':{'content':'{"kind":"YES"}'}}]}))
        self.assertEqual(p.interpret(Dialogue(request(),ROOT/'agent.json'),'네').kind,'YES')
        p=self.provider(lambda req:httpx.Response(200,json={'choices':[{'message':{'content':'{"kind":"YES", "value": 42}'}}]}))
        with self.assertRaises(ValidationError): p.interpret(Dialogue(request(),ROOT/'agent.json'),'네')

    def test_terra_request_omits_unsupported_temperature(self):
        def handler(req):
            body=json.loads(req.content)
            self.assertEqual(body['model'],'gpt-6.1-terra')
            self.assertNotIn('temperature',body)
            self.assertEqual(str(req.url),self.s.llm_base_url+'/chat/completions')
            return httpx.Response(200,json={'choices':[{'message':{'content':'{"kind":"YES"}'}}]})
        p=self.provider(handler)
        self.assertEqual(p.interpret(Dialogue(request(),ROOT/'agent.json'),'네').kind,'YES')

    def test_llm_http_error_includes_response_detail(self):
        p=self.provider(lambda req:httpx.Response(400,json={'error':{'message':'unsupported parameter: temperature'}}))
        with self.assertRaises(httpx.HTTPStatusError) as error:
            p.interpret(Dialogue(request(),ROOT/'agent.json'),'네')
        self.assertIn('400 Bad Request',str(error.exception))
        self.assertIn('unsupported parameter: temperature',str(error.exception))

class APITests(unittest.TestCase):
    def test_post_get_auth_validation_and_unknown_id(self):
        with tempfile.TemporaryDirectory() as tmp:
            s=settings(Path(tmp))
            def factory(s,store,providers):
                def runner(cid,req,stop):
                    store.started(cid); store.connected(cid)
                    store.save_answers(cid,[{'questionId':'q1','field':'storeName','value':'새봄식당'}]); store.finish(cid)
                return runner
            with TestClient(create_app(s,factory)) as client:
                headers={'Authorization':'Bearer api-test'}
                self.assertEqual(client.post('/v1/verification-call',json=PAYLOAD).status_code,401)
                res=client.post('/v1/verification-call',json=PAYLOAD,headers=headers)
                self.assertEqual(res.status_code,202)
                cid=res.json()['verificationId']
                deadline=time.monotonic()+2
                while time.monotonic()<deadline:
                    result=client.get('/v1/verification-call/'+cid,headers=headers)
                    if result.json()['status']=='COMPLETED': break
                    time.sleep(.01)
                self.assertEqual(result.json()['answers'][0]['value'],'새봄식당')
                self.assertIsNotNone(result.json()['connectedAt'])
                self.assertEqual(client.get('/v1/verification-call/00000000-0000-0000-0000-000000000000',headers=headers).status_code,404)
                self.assertEqual(client.get('/v1/verification-call/not-a-uuid',headers=headers).status_code,422)
                self.assertEqual(client.post('/v1/verification-call',json={},headers=headers).status_code,422)
                self.assertEqual(client.get('/healthz').status_code,200)
                self.assertIn('/v1/verification-call',client.get('/openapi.json').json()['paths'])

if __name__=='__main__': unittest.main()
