import struct, re, subprocess, sys
path = sys.argv[1]
r = subprocess.check_output(['aarch64-linux-gnu-readelf','-S','-W',path]).decode()
off=None
for line in r.splitlines():
    m = re.search(r'__versions\s+PROGBITS\s+([0-9a-f]+)\s+([0-9a-f]+)\s+([0-9a-f]+)', line)
    if m: off=int(m.group(2),16); sz=int(m.group(3),16)
assert off, 'no __versions'
data = bytearray(open(path,'rb').read())
n = sz//64
for i in range(n):
    base = off + i*64
    # zero CRC + name (8 + 56 bytes); entry becomes fully empty -> loader accepts
    for j in range(base, base+64):
        data[j] = 0
open(path,'wb').write(bytes(data))
print(f'zeroed {n} __version entries')
