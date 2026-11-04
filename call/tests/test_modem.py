import contextlib
import io
import tempfile
import time
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch
from verification.hardware.modem import EP06Modem

class ModemTests(unittest.TestCase):
    def setUp(self):
        output = contextlib.redirect_stdout(io.StringIO())
        output.__enter__()
        self.addCleanup(output.__exit__, None, None, None)
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.folder = Path(directory.name)

    def test_modem_audio_tuning_changes_downlink_and_restores_both_values(self):
        modem = EP06Modem('unused')
        state = {'mode': 0, 'gain': 20577, 'mic': (1200, 800), 'sidetone': 123}
        commands = []

        def answer(command):
            commands.append(command)
            if command == 'AT+QAUDMOD?':
                return [f'+QAUDMOD: {state["mode"]}', 'OK']
            if command == 'AT+QRXGAIN?':
                return [f'+QRXGAIN: {state["gain"]}', 'OK']
            if command == 'AT+QMIC?':
                return [f'+QMIC: {state["mic"][0]},{state["mic"][1]}', 'OK']
            if command == 'AT+QSIDET?':
                return [f'+QSIDET: {state["sidetone"]}', 'OK']
            if command.startswith('AT+QAUDMOD='):
                state['mode'] = int(command.rsplit('=', 1)[1])
                state['gain'] = 1000 if state['mode'] == 3 else 20577
                return ['OK']
            if command.startswith('AT+QRXGAIN='):
                state['gain'] = int(command.rsplit('=', 1)[1])
                return ['OK']
            if command.startswith('AT+QMIC='):
                state['mic'] = tuple(map(int, command.split('=', 1)[1].split(',')))
                return ['OK']
            if command.startswith('AT+QSIDET='):
                state['sidetone'] = int(command.rsplit('=', 1)[1])
                return ['OK']
            self.fail(command)

        with patch.object(modem, 'at', side_effect=answer):
            modem.configure_audio(3, 3000, (16384, 1024), 0)
            self.assertEqual(state, {'mode': 3, 'gain': 3000, 'mic': (16384, 1024), 'sidetone': 0})
            modem.restore_audio_tuning()
        self.assertEqual(state, {'mode': 0, 'gain': 20577, 'mic': (1200, 800), 'sidetone': 123})
        self.assertEqual(commands[-4:], ['AT+QAUDMOD=0', 'AT+QRXGAIN=20577',
                                         'AT+QMIC=1200,800', 'AT+QSIDET=123'])

    def test_modem_audio_opt_out_only_queries_settings(self):
        modem = EP06Modem('unused')
        responses = {
            'AT+QAUDMOD?': ['+QAUDMOD: 0', 'OK'],
            'AT+QRXGAIN?': ['+QRXGAIN: 1000', 'OK'],
            'AT+QMIC?': ['+QMIC: 1000,500', 'OK'],
            'AT+QSIDET?': ['+QSIDET: 100', 'OK'],
        }
        with patch.object(modem, 'at', side_effect=lambda command: responses[command]) as at:
            modem.configure_audio(None, None)
            modem.restore_audio_tuning()
        self.assertTrue(all(call.args[0].endswith('?') for call in at.call_args_list))

    def test_modem_rejects_unreadable_baseline_before_writing(self):
        modem = EP06Modem('unused')
        with patch.object(modem, 'at', return_value=['ERROR']) as at:
            with self.assertRaisesRegex(RuntimeError, 'QAUDMOD 조회 실패'):
                modem.configure_audio(3, None)
        self.assertEqual(at.call_count, 1)

    def test_modem_enables_serial_voice_forwarding(self):
        modem = EP06Modem('unused')
        with patch.object(modem, 'at', side_effect=[
            ['OK'], ['+QGPSCFG: "outport",none', 'OK'], ['OK'], ['+QPCMV: 1,0', 'OK'],
        ]) as at:
            modem.initialize()
        self.assertEqual([call.args[0] for call in at.call_args_list],
                         ['ATE0', 'AT+QGPSCFG="outport"', 'AT+QPCMV=1,0', 'AT+QPCMV?'])

    def test_reset_happens_before_volatile_audio_configuration(self):
        modem = EP06Modem('unused')
        calls = []

        def answer(command):
            calls.append(command)
            if command == 'AT+QGPSCFG="outport"':
                return ['+QGPSCFG: "outport",none', 'OK']
            if command == 'AT+QPCMV?':
                return ['+QPCMV: 1,0', 'OK']
            return ['OK']

        with patch.object(modem, 'at', side_effect=answer), \
             patch.object(modem, 'reboot', side_effect=lambda: calls.append('reboot')):
            modem.initialize(reset_before_call=True)
        self.assertEqual(calls[:3], ['ATE0', 'reboot', 'AT+QGPSCFG="outport"'])

    def test_reboot_waits_for_lte_registration(self):
        modem = EP06Modem('unused')
        with patch.object(modem, 'at', side_effect=[
            ['+CEREG: 0,2', 'OK'], ['+CEREG: 0,1', 'OK'],
        ]) as at, patch('verification.hardware.modem.time.sleep'):
            modem._wait_for_network(time.monotonic() + 2)
        self.assertEqual(at.call_count, 2)

    def test_gps_nmea_output_is_restored_after_voice_session(self):
        modem = EP06Modem('unused')
        commands = []

        def answer(command):
            commands.append(command)
            if command == 'AT+QGPSCFG="outport"':
                return ['+QGPSCFG: "outport",usbnmea', 'OK']
            return ['OK']

        with patch.object(modem, 'at', side_effect=answer):
            modem.disable_gps_output_on_voice_port()
            modem.restore_gps_outport()
        self.assertEqual(commands, ['AT+QGPSCFG="outport"',
                                    'AT+QGPSCFG="outport","none"',
                                    'AT+QGPSCFG="outport","usbnmea"'])

    def test_modem_rejects_unsupported_serial_voice_mode(self):
        modem = EP06Modem('unused')
        with patch.object(modem, 'at', side_effect=[
            ['OK'], ['+QGPSCFG: "outport",none', 'OK'], ['ERROR'],
        ]) as at:
            with self.assertRaisesRegex(RuntimeError, 'QPCMV=1,0'):
                modem.initialize()
        self.assertEqual(at.call_count, 3)

    @patch('verification.hardware.modem.serial.Serial')
    def test_modem_disables_voice_forwarding_on_shutdown(self, serial):
        with EP06Modem('unused') as modem:
            modem._voice_forwarding = True
            serial.return_value.readline.return_value = b'OK\r\n'
        self.assertEqual([call.args[0] for call in serial.return_value.write.call_args_list],
                         [b'AT+CHUP\r\n', b'AT+QPCMV=0\r\n'])

    def test_modem_rejects_wrong_forwarding_mode(self):
        modem = EP06Modem('unused')
        with patch.object(modem, 'at', side_effect=[
            ['OK'], ['+QGPSCFG: "outport",none', 'OK'], ['OK'], ['+QPCMV: 0,0', 'OK'],
        ]):
            with self.assertRaisesRegex(RuntimeError, '상태 확인 실패'):
                modem.initialize()

    def test_modem_filters_non_outgoing_voice_calls(self):
        modem = EP06Modem('unused')
        with patch.object(modem, 'at', return_value=[
            '+CLCC: 1,1,0,0,0', '+CLCC: 2,0,0,1,0', '+CLCC: 3,0,3,0,0', 'OK'
        ]):
            self.assertEqual(modem.outgoing_voice_status(), 3)
        with patch.object(modem, 'at', return_value=['ERROR']):
            with self.assertRaises(RuntimeError):
                modem.outgoing_voice_status()
        with patch.object(modem, 'at', return_value=['NO CARRIER']):
            self.assertEqual(modem.outgoing_voice_status(), -1)

    def test_no_carrier_during_answer_wait_records_release_cause(self):
        modem = EP06Modem('unused')
        with patch.object(modem, 'at', side_effect=[
            ['NO CARRIER'], ['+CEER: 16', 'OK'], ['+QCFG: "ims",1,0', 'OK'],
        ]) as at:
            self.assertFalse(modem.wait_for_answer())
        self.assertEqual([call.args[0] for call in at.call_args_list],
                         ['AT+CLCC', 'AT+CEER', 'AT+QCFG="ims"'])

    def test_immediate_no_carrier_is_not_a_clcc_error(self):
        modem = EP06Modem('unused')
        with patch.object(modem, 'at', side_effect=[
            ['NO CARRIER'], ['+CEER: 17', 'OK'], ['+QCFG: "ims",1,1', 'OK'],
        ]) as at:
            self.assertFalse(modem.dial('01012345678'))
        self.assertEqual([call.args[0] for call in at.call_args_list][1:],
                         ['AT+CEER', 'AT+QCFG="ims"'])

    @patch('verification.hardware.modem.time.sleep')
    def test_wait_for_answer_handles_connect_and_early_disconnect(self, sleep):
        modem = EP06Modem('unused')
        for statuses, expected in [([None, 2, 3, 0], True), ([2, None], False)]:
            with self.subTest(statuses=statuses):
                with patch.object(modem, 'outgoing_voice_status', side_effect=statuses), \
                     patch.object(modem, 'report_call_release'):
                    self.assertEqual(modem.wait_for_answer(), expected)

    @patch('verification.hardware.modem.serial.Serial')
    def test_modem_closes_on_interrupt_and_hangup_failure(self, serial):
        connection = serial.return_value
        connection.readline.return_value = b'OK\r\n'
        with self.assertRaises(KeyboardInterrupt):
            with EP06Modem('unused'):
                raise KeyboardInterrupt
        connection.write.assert_called_with(b'AT+CHUP\r\n')
        connection.close.assert_called_once()
        connection.reset_mock()
        connection.write.side_effect = OSError('disconnected')
        with self.assertRaises(OSError):
            with EP06Modem('unused'):
                pass
        connection.close.assert_called_once()
