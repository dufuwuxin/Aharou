# SOMCP local ELF integration

Upstream: https://github.com/bilieebiliee1-design/SOMCP
Pinned commit: `f01f059bf4c903255464f1a073431c41ebd74eb5`
License: **AGPL-3.0-only**. Preserve the accompanying LICENSE and NOTICE.
This integration and the imported parser are AGPL-3.0-only. Original Aharou source files retain their original notices. Distributions containing this integration must retain these notices and provide corresponding source; do not describe the combined integration as GPL-only or proprietary.

## Imported scope

Only `ElfParser.kt` and `ElfModels.kt` are imported into `core-agent`, under their original package. `provenance.json` records the upstream source paths and SHA-256 digests. License and notice copies are also bundled in `app/src/main/assets/licenses/somcp`, accessible in the native ELF preview.

The shared local service is used by the `elf_analyze` built-in tool and by file preview after checking the ELF magic. It reads existing authorized file backends; it never loads, links, executes, patches or uploads target binaries, and starts no listener or external MCP service.

Supported: ELF headers, program and section metadata, symbols, dynamic imports/exports, DT_NEEDED dependencies, bounded section strings and relocations. Both ELF32/ELF64 and byte orders are supported within explicitly checked limits. Extended numbering, ambiguous multiple symbol tables, oversized files and excessive record counts are rejected explicitly.

Not imported: SOMCP's UI/server, Rizin, LIEF, decompilers, Flutter/Blutter, patching, Frida, unidbg, Android probes, APK repackaging, cloud/tunnel services, telemetry and integrity/self-artifact guards. These backends are not advertised as available by this integration.

## Changes from upstream (2026-11-19)

- Added source/license headers to the two imported files.
- Validate integer ranges, table extents and record budgets before parsing.
- Bound text allocations and string scanning; support thread interruption.
- Preserve the null symbol entry so relocation indexes keep their ELF meaning.
- Guard byte slicing against overflow and SHT_NOBITS.
- Expose pagination/filtering with bounded result pages; preserve raw numeric addresses as hexadecimal.
- Resolve DT_NEEDED using PT_LOAD/DT_STRTAB, including sectionless ELF.
- Count dynamic imports/exports separately from ordinary symbol-table records.

## Operational limits

32 MiB input; at most 4096 program or section headers; at most 100000 structured records; 8 MiB combined text budget. String extraction is explicitly bounded to selected data sections, 20000 candidates and 1024 displayed characters per candidate. Pages contain at most 100 records and approximately 64 KiB JSON data. Reading and analysis run off the UI thread and are cancellable. Content-hash caching retains only one parsed input; file access and authorization are never cached.
