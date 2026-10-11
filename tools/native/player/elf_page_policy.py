"""Check RELRO protection against actual LOAD ranges, including complete segments.

Bionic does not require an aligned RELRO end when the whole containing LOAD is
protected. Check page-rounded protection for conflicting writable/executable data
instead of rejecting a standard NDK runtime because its raw end is unaligned.
Reference: platform/bionic, android16-qpr2-release, linker_phdr_16kib_compat.cpp,
phdr_table_get_relro_min_align, and linker_phdr.cpp, _phdr_table_set_gnu_relro_prot.
"""

PAGE = 16384


def relro_page_layout(loads, regions, page_size=PAGE):
    """loads: (virtual address, memory size, ELF flags); regions: (address, size)."""
    problems = []
    for start, size in regions:
        end = start + size
        containing = [(address, length, flags) for address, length, flags in loads
                      if address <= start < address + length and
                      (end <= address + length or start == address)]
        if size <= 0 or not containing:
            problems.append('relro_not_contained_in_load')
            continue
        protected_start = start - start % page_size
        protected_end = (end + page_size - 1) // page_size * page_size
        for address, length, flags in loads:
            if not flags & 3:  # PF_X | PF_W; removing read access is not involved.
                continue
            load_end = address + length
            before = max(address, protected_start) < min(load_end, start)
            after = max(address, end) < min(load_end, protected_end)
            executable = flags & 1 and max(address, start) < min(load_end, end)
            if before or after or executable:
                problems.append('relro_protection_overlaps_mutable_or_executable_data')
    return {'hasRelro': bool(regions),
            'relroEnds16KiBAligned': all((address + size) % PAGE == 0 for address, size in regions),
            'relroPageLayoutCompatible': not problems, 'relroProblems': sorted(set(problems))}
