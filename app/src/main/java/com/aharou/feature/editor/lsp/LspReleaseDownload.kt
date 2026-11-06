package com.aharou.feature.editor.lsp

import com.aharou.core.util.shellQuote

/** Only GitHub's official release API can supply trust metadata, never the download mirror. */
internal object LspReleaseDownload {
    fun script(spec: ServerSpec): String? {
        val repo = spec.releaseRepo ?: return null
        val version = spec.releaseVersion ?: return null
        val entry = spec.releaseEntryRelPath ?: return null
        val pkg = spec.command
        require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo))
        require(Regex("[A-Za-z0-9_.+-]+").matches(version) && version != "." && version != "..")
        require(Regex("[A-Za-z0-9_-]+").matches(pkg))
        require(entry.split('/').all { Regex("[A-Za-z0-9_.+-]+").matches(it) && it != "." && it != ".." })
        val choices = listOf("linux-arm64", "linux-x64", "linux-armhf").joinToString("\n            ") { arch ->
            val asset = spec.releaseAssetFor?.invoke(arch) ?: "$pkg-$version-$arch.tar.gz"
            require(Regex("""[A-Za-z0-9][A-Za-z0-9_.+-]*\.tar\.gz""").matches(asset))
            "  $arch) asset=${shellQuote(asset)} ;;"
        }
        return """
            set -eu
            command -v python3 >/dev/null || { echo 'Python 3 required for trusted release verification'; exit 1; }
            command -v curl >/dev/null || { echo 'curl required'; exit 1; }
            case "${'$'}(uname -m)" in
              aarch64|arm64) asset_arch=linux-arm64 ;;
              x86_64|amd64) asset_arch=linux-x64 ;;
              armv7l|armhf) asset_arch=linux-armhf ;;
              *) echo 'Unsupported release architecture'; exit 1 ;;
            esac
            case "${'$'}asset_arch" in
            $choices
            esac
            mkdir -p "${'$'}HOME/.aharou/lsp" "${'$'}HOME/.aharou/bin"
            tmp=${'$'}(mktemp -d "${'$'}HOME/.aharou/lsp/.$pkg.XXXXXXXX")
            published=0
            destination="${'$'}HOME/.aharou/lsp/$pkg-${'$'}{tmp##*/}"
            cleanup() { rm -rf -- "${'$'}tmp"; if [ "${'$'}published" = 0 ]; then rm -rf -- "${'$'}destination"; fi; }
            trap cleanup EXIT
            trap 'exit 1' HUP INT TERM
            curl -fsSL --proto '=https' --tlsv1.2 --connect-timeout 20 --max-time 120 -H 'Accept: application/vnd.github+json' \
              -o "${'$'}tmp/release.json" 'https://api.github.com/repos/$repo/releases/tags/$version'
            origin="https://github.com/$repo/releases/download/$version/${'$'}asset"
            curl -fsSL --proto '=https' --tlsv1.2 --connect-timeout 20 --max-time 180 -o "${'$'}tmp/archive.tgz" "${'$'}origin"
            python3 - "${'$'}tmp" "${'$'}asset" ${shellQuote(entry)} <<'AHAROU_VERIFY'
            import hashlib, json, pathlib, shutil, sys, tarfile
            root = pathlib.Path(sys.argv[1])
            assets = [a for a in json.loads((root / 'release.json').read_text())['assets'] if a.get('name') == sys.argv[2]]
            if len(assets) != 1:
                raise SystemExit('Official release asset missing or ambiguous')
            digest = assets[0].get('digest') or ''
            if not digest.startswith('sha256:') or len(digest) != 71 or any(c not in '0123456789abcdef' for c in digest[7:]):
                raise SystemExit('Official SHA-256 digest unavailable; use the package-manager installation')
            if hashlib.sha256((root / 'archive.tgz').read_bytes()).hexdigest() != digest[7:]:
                raise SystemExit('Release checksum mismatch; existing installation preserved')
            payload = root / 'payload'
            payload.mkdir()
            with tarfile.open(root / 'archive.tgz', 'r:gz') as archive:
                for member in archive.getmembers():
                    path = pathlib.PurePosixPath(member.name)
                    if path.is_absolute() or '..' in path.parts or not (member.isfile() or member.isdir()):
                        raise SystemExit('Unsafe archive path or entry type')
                    dest = payload.joinpath(*path.parts)
                    if member.isdir():
                        dest.mkdir(parents=True, exist_ok=True)
                    else:
                        dest.parent.mkdir(parents=True, exist_ok=True)
                        with archive.extractfile(member) as src, dest.open('xb') as out:
                            shutil.copyfileobj(src, out)
                        dest.chmod(member.mode & 0o777)
            executable = payload / sys.argv[3]
            if not executable.is_file():
                raise SystemExit('Release executable missing')
            executable.chmod(executable.stat().st_mode | 0o100)
            AHAROU_VERIFY
            mv -- "${'$'}tmp/payload" "${'$'}destination"
            printf '#!/bin/sh\nexec "%s/$entry" "${'$'}@"\n' "${'$'}destination" > "${'$'}tmp/launcher"
            chmod +x "${'$'}tmp/launcher"
            mv -f -- "${'$'}tmp/launcher" "${'$'}HOME/.aharou/bin/$pkg"
            published=1
            echo 'Verified official release installed'
        """.trimIndent()
    }
}
