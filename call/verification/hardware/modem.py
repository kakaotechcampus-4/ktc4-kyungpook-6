"""Quectel EP06-E 시리얼 연결, 발신 및 통화 상태 관리."""

import re
import time

import serial

from .telemetry import EventLog


# +CLCC: <idx>,<dir>,<stat>,<mode>,<mpty>,...
# dir=0: 발신, stat=0: 통화 연결, mode=0: 음성통화
CLCC_PATTERN = re.compile(
    r"^\+CLCC:\s*(?P<idx>\d+)\s*,\s*(?P<dir>\d+)\s*,"
    r"\s*(?P<stat>\d+)\s*,\s*(?P<mode>\d+)\s*,"
)


def _read_audio_value(lines: list[str], name: str) -> int:
    if 'OK' not in lines:
        raise RuntimeError(f'{name} 조회 실패: {lines}')
    pattern = re.compile(rf'^\+{name}:\s*(\d+)\s*$')
    matches = [pattern.fullmatch(line) for line in lines]
    values = [int(match.group(1)) for match in matches if match]
    if len(values) != 1:
        raise RuntimeError(f'{name} 응답 해석 실패: {lines}')
    return values[0]


def _read_mic_values(lines: list[str]) -> tuple[int, int]:
    if 'OK' not in lines:
        raise RuntimeError(f'QMIC 조회 실패: {lines}')
    values = [re.fullmatch(r'\+QMIC:\s*(\d+)\s*,\s*(\d+)', line) for line in lines]
    values = [match for match in values if match]
    if len(values) != 1:
        raise RuntimeError(f'QMIC 응답 해석 실패: {lines}')
    return int(values[0][1]), int(values[0][2])


class EP06Modem:
    def __init__(self, port: str, log: EventLog | None = None):
        self.port = port
        self.connection = None
        self.log = log if log is not None else EventLog()
        self._voice_forwarding = False
        self._restore_audio_mode = None
        self._restore_rx_gain = None
        self._restore_mic_gain = None
        self._restore_sidetone = None
        self._restore_gps_outport = None
        self._last_call_terminal_response = []
        self.abort_requested = None
        self._closing = False

    def __enter__(self):
        self.connection = serial.Serial(self.port, 115200, timeout=0.2, write_timeout=2)
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        if self.connection is None:
            return
        self._closing = True
        try:
            self.at("AT+CHUP")
        finally:
            try:
                try:
                    self.restore_audio_tuning()
                finally:
                    try:
                        if self._voice_forwarding:
                            self.at('AT+QPCMV=0')
                    finally:
                        self.restore_gps_outport()
            finally:
                self._voice_forwarding = False
                self.connection.close()
                self.connection = None

    def reboot(self, timeout: float = 90) -> None:
        """CFUN 재부팅 뒤 AT 포트 재등장을 기다립니다. 오디오 설정 전에 호출합니다."""
        with self.log.span('modem.reboot'):
            if 'OK' not in self.at('AT+CFUN=1,1', timeout=10):
                raise RuntimeError('EP06-E 재부팅 명령 실패')
            self.connection.close()
            self.connection = None
            deadline = time.monotonic() + timeout
            last_error = None
            time.sleep(2)
            while time.monotonic() < deadline:
                self._check_abort()
                try:
                    candidate = serial.Serial(self.port, 115200, timeout=0.2, write_timeout=2)
                    self.connection = candidate
                    if 'OK' in self.at('ATE0', timeout=2):
                        self._wait_for_network(deadline)
                        return
                    candidate.close()
                    self.connection = None
                except (OSError, serial.SerialException) as exc:
                    last_error = exc
                    if self.connection is not None:
                        self.connection.close()
                        self.connection = None
                time.sleep(1)
            raise RuntimeError(f'재부팅 후 AT 포트를 다시 열지 못했습니다: {self.port}') from last_error

    def _wait_for_network(self, deadline: float) -> None:
        while time.monotonic() < deadline:
            self._check_abort()
            response = self.at('AT+CEREG?', timeout=2)
            if 'OK' in response and any(re.match(r'^\+CEREG:\s*\d+\s*,\s*[15](?:\s*,|\s*$)', line)
                                         for line in response):
                self.log.emit('modem.network_ready', response=response)
                return
            time.sleep(1)
        raise RuntimeError('재부팅 후 LTE 망 등록을 확인하지 못했습니다 (AT+CEREG?)')

    def initialize(self, *, reset_before_call: bool = False) -> None:
        if 'OK' not in self.at('ATE0'):
            raise RuntimeError('모뎀 초기화 실패: ATE0')
        if reset_before_call:
            self.reboot()
        self.disable_gps_output_on_voice_port()
        # EP06-E의 UAC가 아닌 USB NMEA 직렬 PCM 전달 모드.
        if 'OK' not in self.at('AT+QPCMV=1,0'):
            raise RuntimeError('모뎀 초기화 실패: AT+QPCMV=1,0')
        self._voice_forwarding = True
        state = self.at('AT+QPCMV?')
        if 'OK' not in state or not any(re.fullmatch(r'\+QPCMV:\s*1\s*,\s*0', line) for line in state):
            raise RuntimeError(f'USB 직렬 음성 전달 상태 확인 실패: {state}')

    def disable_gps_output_on_voice_port(self) -> None:
        lines = self.at('AT+QGPSCFG="outport"')
        matches = [re.fullmatch(r'\+QGPSCFG:\s*"outport"\s*,\s*"?(none|usbnmea|uartdebug)"?', line)
                   for line in lines]
        values = [match.group(1) for match in matches if match]
        if 'OK' not in lines or len(values) != 1:
            raise RuntimeError(f'GNSS 출력 포트 조회 실패: {lines}')
        self.log.emit('modem.gps_outport', value=values[0])
        if values[0] == 'usbnmea':
            self._restore_gps_outport = values[0]
            if 'OK' not in self.at('AT+QGPSCFG="outport","none"'):
                raise RuntimeError('음성 포트의 GNSS 출력 중지 실패')

    def restore_gps_outport(self) -> None:
        if self._restore_gps_outport is None:
            return
        value = self._restore_gps_outport
        try:
            if 'OK' not in self.at(f'AT+QGPSCFG="outport","{value}"'):
                raise RuntimeError('GNSS 출력 포트 복원 실패')
            self.log.emit('modem.gps_outport_restored', value=value)
        except Exception as exc:
            self.log.emit('modem.gps_outport_restored', status='error', error=str(exc))
        finally:
            self._restore_gps_outport = None

    def dial(self, phone_number: str) -> bool:
        if not re.fullmatch(r"\+?\d{7,15}", phone_number):
            raise ValueError("PHONE_NUMBER는 숫자 7~15자리(선택적으로 앞에 +)여야 합니다.")
        result = self.at(f"ATD{phone_number};", timeout=4)
        if any(value in result for value in ('NO CARRIER', 'BUSY', 'NO ANSWER')):
            self.report_call_release(result)
            return False
        if "OK" not in result:
            raise RuntimeError(f"발신 실패: {result}")
        return True

    def report_call_release(self, response: list[str]) -> None:
        """통화 실패 직후 CEER를 조회해 네트워크 해제 사유를 보존합니다."""
        detail = {'response': response}
        for name, command in (('ceer', 'AT+CEER'), ('ims', 'AT+QCFG="ims"')):
            try:
                detail[name] = self.at(command)
            except Exception as exc:
                detail[name] = [f'{type(exc).__name__}: {exc}']
        self.log.emit('modem.call_release', **detail)

    def configure_audio(self, mode: int | None, rx_gain: int | None,
                        mic_gain: tuple[int, int] | None = None,
                        sidetone: int | None = None) -> None:
        """현재 수신 설정을 기록하고, 이번 통화에만 시험값을 적용합니다."""
        current_mode = _read_audio_value(self.at('AT+QAUDMOD?'), 'QAUDMOD')
        current_gain = _read_audio_value(self.at('AT+QRXGAIN?'), 'QRXGAIN')
        current_mic = _read_mic_values(self.at('AT+QMIC?'))
        current_sidetone = _read_audio_value(self.at('AT+QSIDET?'), 'QSIDET')
        self.log.emit('modem.audio_baseline', mode=current_mode, rx_gain=current_gain,
                      mic_gain=current_mic, sidetone=current_sidetone)
        if mode is not None and mode != current_mode:
            self._restore_audio_mode = current_mode
            self._restore_rx_gain = current_gain
            self._restore_mic_gain = current_mic
            self._restore_sidetone = current_sidetone
            self._set_audio_value('QAUDMOD', mode)
        # 오디오 모드 변경은 이득 기본값도 바꿀 수 있으므로, 변경 후 값을 다시 읽습니다.
        gain_after_mode = _read_audio_value(self.at('AT+QRXGAIN?'), 'QRXGAIN')
        if rx_gain is not None and rx_gain != gain_after_mode:
            self._restore_rx_gain = current_gain
            self._set_audio_value('QRXGAIN', rx_gain)
        mic_after_mode = _read_mic_values(self.at('AT+QMIC?'))
        if mic_gain is not None and mic_gain != mic_after_mode:
            self._restore_mic_gain = current_mic
            self._set_audio_value('QMIC', f'{mic_gain[0]},{mic_gain[1]}')
        sidetone_after_mode = _read_audio_value(self.at('AT+QSIDET?'), 'QSIDET')
        if sidetone is not None and sidetone != sidetone_after_mode:
            self._restore_sidetone = current_sidetone
            self._set_audio_value('QSIDET', sidetone)
        applied_mode = _read_audio_value(self.at('AT+QAUDMOD?'), 'QAUDMOD')
        applied_gain = _read_audio_value(self.at('AT+QRXGAIN?'), 'QRXGAIN')
        applied_mic = _read_mic_values(self.at('AT+QMIC?'))
        applied_sidetone = _read_audio_value(self.at('AT+QSIDET?'), 'QSIDET')
        if (mode is not None and applied_mode != mode) or (
            rx_gain is not None and applied_gain != rx_gain
        ) or (mic_gain is not None and applied_mic != mic_gain) or (
            sidetone is not None and applied_sidetone != sidetone
        ):
            raise RuntimeError(f'EP06 오디오 설정 확인 실패: mode={applied_mode}, rx_gain={applied_gain}, mic={applied_mic}, sidetone={applied_sidetone}')
        self.log.emit('modem.audio_applied', mode=applied_mode, rx_gain=applied_gain,
                      mic_gain=applied_mic, sidetone=applied_sidetone)

    def _set_audio_value(self, name: str, value: int | str) -> None:
        response = self.at(f'AT+{name}={value}')
        if 'OK' not in response:
            raise RuntimeError(f'AT+{name}={value} 설정 실패: {response}')

    def restore_audio_tuning(self) -> None:
        """통화 종료 시 원래 설정 복원. 실패해도 나머지 설정과 시리얼 정리를 계속합니다."""
        errors = []
        for name, attribute in (('QAUDMOD', '_restore_audio_mode'),
                                ('QRXGAIN', '_restore_rx_gain'),
                                ('QMIC', '_restore_mic_gain'),
                                ('QSIDET', '_restore_sidetone')):
            value = getattr(self, attribute)
            if value is None:
                continue
            try:
                argument = ','.join(map(str, value)) if isinstance(value, tuple) else value
                self._set_audio_value(name, argument)
                self.log.emit('modem.audio_restored', setting=name, value=value)
            except Exception as exc:
                errors.append(f'{name}: {exc}')
            finally:
                setattr(self, attribute, None)
        if errors:
            self.log.emit('modem.audio_restore', status='error', errors=errors)

    def at(self, command, timeout=2.5):
        """AT 명령을 전송하고 최종 응답까지 읽습니다."""
        self._check_abort()
        label = 'ATD<number>;' if command.startswith('ATD') else command
        with self.log.span('modem.at', command=label) as result:
            lines = self._exchange(command, timeout)
            result['response'] = lines
            result['accepted'] = 'OK' in lines
            return lines

    def _exchange(self, command, timeout):
        self.connection.write((command + "\r\n").encode())
        lines = []
        end = time.monotonic() + timeout
        while time.monotonic() < end:
            self._check_abort()
            line = self.connection.readline().decode(errors="replace").strip()
            if not line:
                continue
            lines.append(line)
            if line in ("OK", "ERROR", "NO CARRIER", "BUSY", "NO ANSWER"):
                break
            if line.startswith(('+CME ERROR:', '+CMS ERROR:')):
                break
        return lines

    def _check_abort(self):
        if not self._closing and self.abort_requested is not None and self.abort_requested.is_set():
            raise InterruptedError('SERVER_SHUTDOWN')

    def outgoing_voice_status(self):
        """데이터/다른 통화를 제외하고 발신 음성통화 상태만 반환합니다.

        0=통화 중, 2=발신 중, 3=상대방 벨 울림,
        -1=NO CARRIER/BUSY/NO ANSWER, None=음성통화 없음.
        """
        lines = self.at("AT+CLCC")
        if any(value in lines for value in ('NO CARRIER', 'BUSY', 'NO ANSWER')):
            self._last_call_terminal_response = lines
            return -1
        if "OK" not in lines:
            raise RuntimeError(f"AT+CLCC 상태 조회 실패: {lines}")
        for line in lines:
            match = CLCC_PATTERN.match(line)
            if not match:
                continue
            if int(match.group("dir")) == 0 and int(match.group("mode")) == 0:
                return int(match.group("stat"))
        return None

    def call_is_active(self):
        return self.outgoing_voice_status() == 0

    def wait_for_answer(self, seconds=45):
        until = time.monotonic() + seconds
        seen_voice_call = False
        previous_status = object()
        labels = {0: "통화 연결됨", 1: "보류 중", 2: "발신 중", 3: "상대방 벨 울림"}
        while time.monotonic() < until:
            self._check_abort()
            status = self.outgoing_voice_status()
            if status != previous_status:
                self.log.emit('modem.call_status', call_state=labels.get(status, '통화 없음' if status is None else status), code=status)
            previous_status = status
            if status == 0:
                return True
            if status == -1:
                self.report_call_release(self._last_call_terminal_response or ['call released during AT+CLCC'])
                return False
            if status is not None:
                seen_voice_call = True
            elif seen_voice_call:
                self.log.emit('modem.call_status', call_state='연결 전 통화 종료')
                self.report_call_release(['voice call disappeared from AT+CLCC'])
                return False
            time.sleep(1)
        self.log.emit('modem.call_status', call_state='응답 대기 시간 초과')
        return False
