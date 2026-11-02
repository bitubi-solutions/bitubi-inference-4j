#!/bin/bash
# Checks, with objdump, that no unit has instructions above its ceiling:
#   vector_simd.o        generic x86-64: no VEX/EVEX instructions (v...), no ymm/zmm/k registers
#   kernels_avx2.o       AVX2+FMA: no zmm, no k masks, no ymm/xmm16..31, no AVX-VNNI (vpdp...)
#   kernels_avxvnni.o    AVX2+FMA+AVX-VNNI: no zmm, no k masks, no ymm/xmm16..31
#   kernels_avx512.o     no ceiling
# Usage: check-isa.sh <object-directory>. Exits with an error if any unit exceeds its ceiling.
set -u
DIR="${1:?usage: check-isa.sh <object-directory>}"
OBJDUMP="${OBJDUMP:-objdump}"
status=0

if ! command -v "$OBJDUMP" >/dev/null 2>&1; then
  echo "check-isa: ERROR objdump not found: $OBJDUMP"
  exit 2
fi
if [ ! -f "$DIR/vector_simd.o" ]; then
  echo "check-isa: ERROR missing $DIR/vector_simd.o"
  exit 1
fi
if ! "$OBJDUMP" -f "$DIR/vector_simd.o" | grep -q 'x86-64'; then
  echo "check-isa: architecture is not x86-64; x86 check skipped"
  exit 0
fi
for obj in kernels_avx2.o kernels_avxvnni.o kernels_avx512.o; do
  if [ ! -f "$DIR/$obj" ]; then
    echo "check-isa: ERROR missing $DIR/$obj"
    status=1
  fi
done
if [ "$status" -ne 0 ]; then exit "$status"; fi

# Instructions of a unit (only the mnemonic and operand columns).
disasm() { "$OBJDUMP" -d --no-show-raw-insn "$1" | grep -E '^\s+[0-9a-f]+:\s'; }

# check <object> <forbidden regex> <description>
check() {
  local obj="$DIR/$1" bad
if [ ! -f "$obj" ]; then echo "check-isa: ERROR missing $obj"; status=1; return; fi
  if ! "$OBJDUMP" -f "$obj" | grep -q 'x86-64'; then echo "check-isa: $1 is not x86-64, skipped"; return 0; fi
  bad=$(disasm "$obj" | grep -E "$2" | head -5)
  if [ -n "$bad" ]; then
    echo "check-isa: ERROR $1 has instructions above the ceiling ($3):"
    echo "$bad"
    status=1
  else
    echo "check-isa: ok $1 ($3, $(disasm "$obj" | wc -l) instructions)"
  fi
}

VEX='^\s+[0-9a-f]+:\s+(notrack |bnd |rep |lock |\{vex\} )?v[a-z0-9]+\s'
REGS='%(ymm|zmm)[0-9]+|%k[0-7]'
HIGH='%(zmm[0-9]+|k[0-7]|[xy]mm(1[6-9]|2[0-9]|3[01]))\b'

check vector_simd.o "$VEX|$REGS" "generic x86-64, no VEX/EVEX"
check kernels_avx2.o "$HIGH|\svpdp[a-z]+\s" "AVX2+FMA"
check kernels_avxvnni.o "$HIGH" "AVX2+FMA+AVX-VNNI"
if ! disasm "$DIR/kernels_avxvnni.o" | grep -Eq '\svpdp[a-z]+[[:space:]]'; then
  echo "check-isa: ERROR kernels_avxvnni.o does not contain AVX-VNNI"
  status=1
fi
if ! disasm "$DIR/kernels_avx512.o" | grep -Eq '%zmm' || ! disasm "$DIR/kernels_avx512.o" | grep -Eq '\svpdp[a-z]+[[:space:]]'; then
  echo "check-isa: ERROR kernels_avx512.o does not contain AVX-512 and VNNI"
  status=1
else
  echo "check-isa: ok kernels_avx512.o (AVX-512+VNNI compiled; not executed here)"
fi
exit $status
