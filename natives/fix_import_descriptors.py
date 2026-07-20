"""Post-link fixer for the GNU-ld + llvm-dlltool import-descriptor off-by-N bug.

The mixed toolchain (rustup windows-gnu + llvm-ar-as-dlltool import libs) can emit an
import thunk array whose IMAGE_IMPORT_DESCRIPTOR points N entries past the array's real
start. The Windows loader binds only from the declared FirstThunk onward, leaving the
leading entries holding hint/name RVAs forever. Any `jmp [rip]` thunk through an orphaned
slot then EXECUTES import metadata -> 0xc0000005 (DEP) on whatever thread first calls it
(observed: kernel32!Sleep from rayon worker idle paths -> intermittent, untraceable JVM
death with no hs_err).

Fix: for each descriptor, walk BACKWARD from the declared FirstThunk/OriginalFirstThunk
while the preceding qword pair looks like a live entry (non-null, name-RVA parallel in
both tables), then rewind both descriptor fields to the true array start. Verifies by
re-scanning .text for indirect calls/jmps into unbound .idata.

Usage: python fix_import_descriptors.py <dll-path>
Exit codes: 0 = clean (fixed or nothing to fix), 1 = orphans remain / error.
"""
import struct
import sys

import pefile


def analyze(pe):
    """Return (orphan_thunk_sites, descriptors) for reporting/verification."""
    idata = next((s for s in pe.sections if s.Name.startswith(b".idata")), None)
    text = next(s for s in pe.sections if s.Name.startswith(b".text"))
    if idata is None:
        return [], []
    ilo = idata.VirtualAddress
    ihi = ilo + max(idata.Misc_VirtualSize, idata.SizeOfRawData)

    bound = []
    for entry in pe.DIRECTORY_ENTRY_IMPORT:
        d = entry.struct
        bound.append((d.FirstThunk, d.FirstThunk + len(entry.imports) * 8))

    def is_bound(rva):
        return any(a <= rva < b for a, b in bound)

    tb = text.get_data()
    trva = text.VirtualAddress
    orphans = []
    for i in range(len(tb) - 6):
        if tb[i] == 0xFF and tb[i + 1] in (0x15, 0x25):
            disp = struct.unpack_from("<i", tb, i + 2)[0]
            target = trva + i + 6 + disp
            if ilo <= target < ihi and not is_bound(target):
                orphans.append((trva + i, target))
    return orphans, bound


def fix(path):
    pe = pefile.PE(path)
    orphans, _ = analyze(pe)
    if not orphans:
        print(f"[fix-imports] {path}: no orphaned thunks — nothing to do")
        return 0

    print(f"[fix-imports] {path}: {len(orphans)} orphaned thunk site(s) — rewinding descriptors")

    image_size = pe.OPTIONAL_HEADER.SizeOfImage

    def q(rva):
        try:
            return struct.unpack("<Q", pe.get_data(rva, 8))[0]
        except Exception:
            return None

    import_dir_rva = pe.OPTIONAL_HEADER.DATA_DIRECTORY[1].VirtualAddress  # IMPORT
    patches = []  # (file_offset, u32 value)
    for idx, entry in enumerate(pe.DIRECTORY_ENTRY_IMPORT):
        d = entry.struct
        ilt, iat = d.OriginalFirstThunk, d.FirstThunk
        if not ilt or not iat:
            continue
        back = 0
        while True:
            nxt = back + 8
            vi, va = q(ilt - nxt), q(iat - nxt)
            # a live leading entry: both tables mirror a plausible hint/name RVA
            if not vi or not va or vi != va or vi >= image_size:
                break
            back = nxt
        if back:
            print(f"  {entry.dll.decode()}: rewinding ILT {ilt:#x}->{ilt-back:#x}, "
                  f"IAT {iat:#x}->{iat-back:#x} ({back // 8} recovered entrie(s))")
            # IMAGE_IMPORT_DESCRIPTOR is 20 bytes: u32 OriginalFirstThunk @+0,
            # TimeDateStamp @+4, ForwarderChain @+8, Name @+12, FirstThunk @+16
            desc_off = pe.get_offset_from_rva(import_dir_rva + idx * 20)
            patches.append((desc_off + 0, ilt - back))
            patches.append((desc_off + 16, iat - back))

    pe.close()
    if not patches:
        print("[fix-imports] ERROR: orphans found but no descriptor could be rewound")
        return 1

    with open(path, "r+b") as f:
        for off, val in patches:
            f.seek(off)
            f.write(struct.pack("<I", val))

    # verify
    pe2 = pefile.PE(path)
    remaining, _ = analyze(pe2)
    pe2.close()
    if remaining:
        print(f"[fix-imports] ERROR: {len(remaining)} orphaned thunk(s) REMAIN after patch")
        for site, tgt in remaining:
            print(f"  site {site:#x} -> {tgt:#x}")
        return 1
    print(f"[fix-imports] OK: {len(patches) // 2} descriptor(s) rewound, no orphaned thunks remain")
    return 0


if __name__ == "__main__":
    sys.exit(fix(sys.argv[1]))
