"""Golden vectors for the Java port of the SDK playback route.

Everything runs through upstream's unmodified functions with pinned randomness, so a Java
implementation can replay the exact same draws and compare byte for byte. Materials are
synthetic: no SDK image, configuration or key from the official app is written out.
usage: python -I generate-sdk-playback-vectors.py <vendor phone_account dir> <out json>

The vendor directory is the `scripts/sdk-playback/vendor/phone_account` subset of
jackotom/hongguo-drama at commit b20413b; it is not part of this repository. Needs Python 3.11
with pycryptodome. The output must stay byte-identical to android/tests/sdk-playback-vectors.json,
which android/tests/SdkPlaybackVectorsTest.java replays.
"""
import base64
import hashlib
import json
import os
import random
import struct
import sys
import uuid
import zlib
from pathlib import Path

sys.path.insert(0, str(sys.argv[1]))
import secrets  # noqa: E402

from Crypto.Cipher import AES, ARC4  # noqa: E402
import phone_account_device as device  # noqa: E402
import phone_account_medusa as medusa  # noqa: E402
import phone_account_medusa_digest as digest_mod  # noqa: E402
import phone_playback_digest_v5 as playback_digest  # noqa: E402
import phone_playback_medusa_v5 as playback_medusa  # noqa: E402
import phone_playback_request_v5 as playback_request  # noqa: E402
import phone_playback_signing_v5 as playback_signing  # noqa: E402
import phone_sdk_bootstrap_v5 as bootstrap  # noqa: E402
import phone_sdk_exchange_v5 as exchange  # noqa: E402
import phone_sdk_materials_v5 as materials  # noqa: E402
from vendor import XLadon  # noqa: E402

PID = 4242
NOW = 1795436789123
SECONDS = NOW // 1000
SIGN_KEY = bytes(range(32))
COMMON_KEY = bytes(range(32, 64))
INNER = bytes(range(64, 80))
FRAME = bytes(range(80, 96))
SUBSTITUTIONS = bytes((index * 7 + 3) & 255 for index in range(1024))
UNAVAILABLE = '!synth00'
MATERIALS = materials.SdkMaterials(SIGN_KEY, COMMON_KEY)
IMAGE = materials.SdkImageParts(FRAME, SUBSTITUTIONS, INNER, UNAVAILABLE)
TOKEN = 'synthetic-session-token-0123456789'
VIDEO = '7706406288325872664'
PARAMS = {
    'aid': '8662', 'app_name': 'novelread', 'channel': 'xiaomi_8662_64', 'version_code': '73932', 'version_name': '7.3.9.32',
    'manifest_version_code': '73932', 'update_version_code': '73932', 'device_platform': 'android', 'os': 'android',
    'ssmix': 'a', 'device_type': '23127PN0CC', 'device_brand': 'xiaomi', 'os_api': '34', 'os_version': '14',
    'resolution': '1200*2670', 'dpi': '460', 'ac': 'wifi', 'language': 'zh', 'openudid': 'a1b2c3d4e5f6a7b8',
    'cdid': '11111111-2222-3333-4444-555555555555', 'tt_data': 'a', '_rticket': '1795436789123', 'ts': '1795436789',
    'package': 'com.phoenix.read', 'first_launch_timestamp': '1795436789', 'last_deeplink_update_version_code': '0',
    'cpu_support64': 'true', 'host_abi': 'arm64-v8a', 'is_guest_mode': '0', 'app_type': 'normal', 'minor_status': '0',
    'appTheme': 'light', 'is_preinstall': '0', 'need_personal_recommend': '1', 'is_android_pad': '0', 'is_android_fold': '0',
    'md': '0', 'cronet_version': '8d40f833_2026-03-03', 'ttnet_version': '4.2.243.31-douyin', 'use_store_region_cookie': '1',
    'device_id': '7300000000000001234', 'iid': '7400000000000005678',
}
COOKIES = {'odin_tt': 'synthetic-odin', 'ttreq': '1.synthetic-request', 'store-region': 'cn', 'store-region-src': 'did'}
CONTEXT = {'params': dict(PARAMS), 'cookies': COOKIES}


class Entropy:
    """Deterministic stand-in for the secrets module that also records every draw in order."""

    def __init__(self):
        self.rng = random.Random(20261123)
        self.log = []

    def _record(self, call, size, raw, rendered):
        self.log.append({'call': call, 'size': size, 'value': rendered})
        return raw

    def bytes(self, size):
        value = bytes(self.rng.getrandbits(8) for _ in range(size))
        return self._record('bytes', size, value, value.hex())

    def bits(self, size):
        value = self.rng.getrandbits(size)
        return self._record('bits', size, value, format(value, 'x'))

    def below(self, bound):
        value = self.rng.randrange(bound)
        return self._record('below', bound, value, format(value, 'x'))

    def hex(self, size):
        value = bytes(self.rng.getrandbits(8) for _ in range(size)).hex()
        return self._record('hex', size, value, value)


ENTROPY = Entropy()
secrets.token_bytes = ENTROPY.bytes
secrets.randbits = ENTROPY.bits
secrets.randbelow = ENTROPY.below
secrets.token_hex = ENTROPY.hex
os.getpid = lambda: PID


def fake_core(payload, selector, seconds):
    return hashlib.sha256(payload + bytes([selector]) + struct.pack('<I', seconds)).digest()[:16]


def encode(value):
    if isinstance(value, (bytes, bytearray)):
        return {'hex': bytes(value).hex()}
    if isinstance(value, dict):
        return {str(key): encode(item) for key, item in value.items()}
    if isinstance(value, (list, tuple)):
        return [encode(item) for item in value]
    return value


VECTORS = []


def vector(name, inputs, run):
    ENTROPY.log = []
    result = run()
    VECTORS.append({'name': name, 'inputs': encode(inputs), 'draws': list(ENTROPY.log), 'output': encode(result)})


def encode_config(sign_key, common_key, nonce, head, tail, filler):
    """Inverse of upstream's decode_app_config, for a synthetic configuration of the real size."""
    message = medusa.protobuf([(8, medusa.protobuf([(1, b'sign_key'), (2, base64.b64encode(sign_key))]))])
    message += medusa.protobuf([(8, medusa.protobuf([(1, b'common_key'), (2, base64.b64encode(common_key))]))])
    message += medusa.protobuf([(15, filler)])
    mask = 0
    for byte in nonce + head + tail:
        mask ^= byte
    payload = bytes(byte ^ mask for byte in reversed(message))
    stream_key = hashlib.sha1(head + b'8662' + nonce).digest()
    plaintext = head + ARC4.new(stream_key).encrypt(payload) + tail
    padding = 16 - len(plaintext) % 16
    padded = plaintext + bytes([padding]) * padding
    key = hashlib.md5(b'8662' + nonce + b'8662').digest()
    raw = nonce + AES.new(key, AES.MODE_ECB).encrypt(padded)
    return base64.b64encode(raw).decode('ascii')


# 1. configuration decode
nonce, head, tail = b'\x11\x22\x33\x44', b'\xaa\xbb\xcc\xdd', b'\xee\xff\x01\x02'
filler = bytes((index * 13 + 1) & 255 for index in range(92))
encoded = encode_config(SIGN_KEY, COMMON_KEY, nonce, head, tail, filler)
assert len(encoded) == 304, len(encoded)
decoded = materials.decode_app_config(encoded.encode('ascii'))
assert decoded.signing_key == SIGN_KEY and decoded.exchange_key == COMMON_KEY
vector('config_decode', {'encoded': encoded}, lambda: {'signing_key': SIGN_KEY, 'exchange_key': COMMON_KEY})

# 2. image constants: the same fixed offsets upstream reads, over a reproducible filler
image = bytearray((index * 31 + 7) & 255 for index in range(4501136))
image[0x665f0:0x66600] = FRAME
image[0xffcf9:0x1000f9] = SUBSTITUTIONS
image[0x66690:0x66698] = INNER[:8]
image[0x9d08c:0x9d094] = bytes(a ^ b for a, b in zip(INNER[8:], b'\x5a' * 8))
image[0x9d118:0x9d120] = b'\x5a' * 8
image[0x66c44:0x66c4c] = UNAVAILABLE.encode('ascii')
vector('image_parts', {}, lambda: {'frame': image[0x665f0:0x66600], 'substitutions': image[0xffcf9:0x1000f9],
                                   'inner': image[0x66690:0x66698] + bytes(a ^ b for a, b in zip(image[0x9d08c:0x9d094], image[0x9d118:0x9d120])),
                                   'unavailable': image[0x66c44:0x66c4c].decode('ascii')})

# 3-5. identity-bound protobuf blocks
vector('device_proof', {'device_id': PARAMS['device_id']}, lambda: medusa.device_proof(PARAMS['device_id']))
vector('bound_device_proof', {'params': PARAMS}, lambda: bootstrap.bound_device_proof(PARAMS))
vector('device_payload', {'params': PARAMS, 'token': None}, lambda: bootstrap.device_payload(PARAMS, UNAVAILABLE, None))
vector('device_payload_token', {'params': PARAMS, 'token': TOKEN}, lambda: bootstrap.device_payload(PARAMS, UNAVAILABLE, TOKEN))

# 6-7. token exchange codec (zlib at level 0 so the frame does not depend on a zlib build)
class LevelZeroZlib:
    """Stands in for the zlib module inside the exchange codec only."""
    error = zlib.error
    decompressobj = staticmethod(zlib.decompressobj)

    @staticmethod
    def compress(data, level=-1):
        engine = zlib.compressobj(0, zlib.DEFLATED, 15)
        return engine.compress(data) + engine.flush()


exchange.zlib = LevelZeroZlib
xtea_data, xtea_key, xtea_nonce = bytes(range(40, 64)), INNER[:16], b'\x01\x02\x03\x04'
xtea_sealed = exchange.xtea_chain(xtea_data, xtea_key, xtea_nonce)
vector('xtea', {'data': xtea_data, 'key': xtea_key, 'nonce': xtea_nonce},
       lambda: {'sealed': xtea_sealed, 'opened': exchange.xtea_chain(xtea_sealed, xtea_key, xtea_nonce, decrypt=True)})
plaintext = bootstrap.device_payload(PARAMS, UNAVAILABLE, None)
exchange_nonce, entropy_byte = b'\x0a\x0b\x0c\x0d', 0xA5
sealed = exchange.encrypt_payload(plaintext, common=COMMON_KEY, inner=INNER, nonce=exchange_nonce, entropy_byte=entropy_byte)
vector('exchange_seal', {'plaintext': plaintext, 'nonce': exchange_nonce, 'entropy': entropy_byte},
       lambda: {'sealed': sealed, 'opened': exchange.decrypt_payload(sealed, common=COMMON_KEY, inner=INNER)})
packet = exchange.exchange_request(plaintext, NOW, common=COMMON_KEY, inner=INNER, nonce=exchange_nonce, entropy_byte=entropy_byte)
vector('exchange_request', {'plaintext': plaintext, 'milliseconds': NOW, 'nonce': exchange_nonce, 'entropy': entropy_byte}, lambda: packet)
answer_plain = medusa.protobuf([(1, TOKEN.encode('ascii')), (2, bootstrap.signed(202))])
answer_sealed = exchange.encrypt_payload(answer_plain, common=COMMON_KEY, inner=INNER, nonce=b'\x11\x12\x13\x14', entropy_byte=0x5A)
answer = medusa.protobuf([(1, 0x20120312 * 2), (2, 2), (6, answer_sealed)])
vector('exchange_answer', {'packet': answer}, lambda: {'token': exchange.exchange_response(answer, common=COMMON_KEY, inner=INNER).token,
                                                       'status': exchange.exchange_response(answer, common=COMMON_KEY, inner=INNER).status})

# 8-12. signing primitives
query = b'aid=8662&app_name=novelread&&version_code=74332/ *~x'
body = b'\x1f\x8b\x08\x00synthetic-body'
for index, (flags, gorgon_nonce) in enumerate(((b'\x02\x00', 0x1234), (b'\x04\x05', 0xBEEF), (b'\x02\x00', 0))):
    vector(f'gorgon_{index}', {'query': query, 'body': body, 'seconds': SECONDS, 'nonce': gorgon_nonce, 'flags': flags},
           lambda flags=flags, gorgon_nonce=gorgon_nonce: playback_signing.gorgon_v5(query, body, SECONDS, gorgon_nonce, flags=flags))
vector('gorgon_empty_body', {'query': query, 'body': b'', 'seconds': SECONDS, 'nonce': 7, 'flags': b'\x02\x00'},
       lambda: playback_signing.gorgon_v5(query, b'', SECONDS, 7, flags=b'\x02\x00'))
query_digest, body_digest = hashlib.new('sm3', query).digest(), hashlib.md5(body).digest()
vector('selector_checksum', {'query_digest': query_digest, 'body_digest': body_digest, 'seconds': SECONDS},
       lambda: {'selector': digest_mod.selector(query_digest, body_digest, SECONDS), 'checksum': digest_mod.checksum(query_digest[:12])})
core_calls = []


def recording_core(payload, selector, seconds):
    core_calls.append({'payload': payload, 'selector': selector, 'seconds': seconds})
    return fake_core(payload, selector, seconds)


vector('digest_framing', {'query': query, 'body': body, 'seconds': SECONDS},
       lambda: {'proof': playback_digest.digest_v5(query, body, SECONDS, core=recording_core), 'core': core_calls[-1]})
# The reference signs the first 16 bytes of a 20-byte digest; a port must slice them inside its adapter
# over the raw digest call. The expected proof is therefore the framing's own, replayed through that adapter.
vector('digest_adapter', {'query': query, 'body': body, 'seconds': SECONDS},
       lambda: playback_digest.digest_v5(query, body, SECONDS, core=lambda payload, selector, seconds: fake_core(payload, selector, seconds)))
vector('advance_ladon', {'previous': 0x01020304, 'allocation': format(0xFEDCBA9876543210, 'x'), 'seconds': SECONDS},
       lambda: bootstrap.advance_ladon(0x01020304, 0xFEDCBA9876543210, SECONDS))
proto = medusa.protobuf([(1, FRAME), (2, 10), (4, '8662'), (30, bytes((index * 3 + 1) & 255 for index in range(240)))])
assert len(proto) >= 229
proof = playback_digest.digest_v5(query, body, SECONDS, core=fake_core)
vector('envelope', {'proto': proto, 'query_digest': query_digest, 'body_proof': proof, 'seconds': SECONDS, 'key_nonce': 0x11223344, 'frame_nonce': 0x55667788},
       lambda: playback_medusa.envelope_v5(proto, query_digest, proof, SECONDS, 0x11223344, 0x55667788, seed=SIGN_KEY, frame=FRAME,
                                           prefix=struct.pack('<Q', 0x0504), substitution_tables=SUBSTITUTIONS))

# 13-16. complete signed requests. The sealed message is captured as well, so a port can diff it field by field.
sealed = {}
reference_envelope = bootstrap.envelope_v5


def capturing_envelope(proto, *rest, **keywords):
    sealed['message'] = proto
    return reference_envelope(proto, *rest, **keywords)


bootstrap.envelope_v5 = capturing_envelope


def playback_signed():
    request = playback_request.prepare_playback_v5(CONTEXT, VIDEO, NOW, token=TOKEN, previous_ladon=0x10203040,
                                                    materials=MATERIALS, image=IMAGE, core=fake_core)
    return dict(request.__dict__, message=sealed['message'])


vector('bootstrap', {'milliseconds': NOW, 'token': None, 'previous_ladon': 0},
       lambda: dict(bootstrap.prepare_bootstrap(CONTEXT, NOW, materials=MATERIALS, image=IMAGE, core=fake_core).__dict__, message=sealed['message']))
vector('bootstrap_token', {'milliseconds': NOW, 'token': TOKEN, 'previous_ladon': 0x01020304},
       lambda: dict(bootstrap.prepare_bootstrap(CONTEXT, NOW, materials=MATERIALS, image=IMAGE, core=fake_core, token=TOKEN, previous_ladon=0x01020304).__dict__,
                    message=sealed['message']))
vector('playback', {'video': VIDEO, 'milliseconds': NOW, 'token': TOKEN, 'previous_ladon': 0x10203040}, playback_signed)

captured = {}
original_encrypt = device.TT.encrypt


def capturing_encrypt(self, data):
    captured['plain'] = data
    return original_encrypt(self, data)


device.TT.encrypt = capturing_encrypt
device.uuid.uuid4 = lambda: uuid.UUID('12345678-1234-5678-1234-567812345678')


def registration():
    url, wire, headers, params = device.prepare_registration(NOW)
    seed = next(item['value'] for item in ENTROPY.log if item['call'] == 'bytes')
    return {'url': url, 'headers': headers, 'params': params, 'plain': captured['plain'], 'seed': bytes.fromhex(seed),
            'openudid': params['openudid'], 'cdid': params['cdid']}


vector('registration', {'milliseconds': NOW, 'uuid': '12345678-1234-5678-1234-567812345678'}, registration)

Path(sys.argv[2]).write_text(json.dumps({'pid': PID, 'params': PARAMS, 'cookies': COOKIES, 'vectors': VECTORS}, indent=1))
print(json.dumps({'vectors': len(VECTORS), 'draws': sum(len(item['draws']) for item in VECTORS), 'out': sys.argv[2]}))
