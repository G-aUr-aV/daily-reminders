#!/usr/bin/env python3
"""Deterministic locally synthesized PCM16 fallback; no downloads or runtime dependency."""
import io
import math
from pathlib import Path
import struct
import wave

def payload():
    out = io.BytesIO()
    with wave.open(out, 'wb') as w:
        w.setparams((1, 2, 16000, 0, 'NONE', 'not compressed'))
        w.writeframes(b''.join(struct.pack('<h', int(5000 * math.sin(2*math.pi*660*n/16000) * min(1,n/400,(16000-n)/400))) if n < 12000 else b'\0\0' for n in range(16000)))
    return out.getvalue()

if __name__ == '__main__':
    target = Path(__file__).resolve().parents[1] / 'app/src/main/res/raw/alarm_fallback.wav'
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(payload())
