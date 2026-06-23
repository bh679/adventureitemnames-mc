#!/usr/bin/env python3
"""Unit tests for scripts/publish-wiki.sh's Downloads page rendering.

Subprocess-based, mirroring scripts/release-notes/test_release_notes.py. Runnable
directly (`python3 scripts/test_publish_wiki.py`) and discoverable by pytest
(bare test_* functions). Local-only — not CI-gated, matching the release-notes
test convention.

The script is exercised offline against scripts/fixtures/releases-multiversion.json
using its RELEASES_FILE (inject releases JSON) and OUTPUT_DIR (dry-run, no wiki
push) hooks, so no network or wiki token is required.
"""
import os
import subprocess
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
REPO_ROOT = os.path.dirname(HERE)
SCRIPT = os.path.join(HERE, "publish-wiki.sh")
FIXTURE = os.path.join(HERE, "fixtures", "releases-multiversion.json")

REPO = "bh679/adventureitemnames-mc"
EMDASH = "—"


def render(**extra_env: str) -> "tuple[str, str, subprocess.CompletedProcess]":
    """Run publish-wiki.sh against the fixture; return (downloads, archive, proc)."""
    out_dir = tempfile.mkdtemp(prefix="wiki-test-")
    env = {
        **os.environ,
        "REPO": REPO,
        "RELEASES_FILE": FIXTURE,
        "OUTPUT_DIR": out_dir,
    }
    env.pop("GITHUB_OUTPUT", None)
    for k, v in extra_env.items():
        if v is None:
            env.pop(k, None)
        else:
            env[k] = v
    proc = subprocess.run(
        ["bash", SCRIPT], cwd=REPO_ROOT, env=env,
        capture_output=True, text=True,
    )
    downloads = archive = ""
    dl_path = os.path.join(out_dir, "Downloads.md")
    arch_path = os.path.join(out_dir, "Downloads-Archive.md")
    if os.path.exists(dl_path):
        with open(dl_path) as f:
            downloads = f.read()
    if os.path.exists(arch_path):
        with open(arch_path) as f:
            archive = f.read()
    return downloads, archive, proc


def cells(row: str) -> "list[str]":
    """Trimmed cell values of a Markdown table row (border pipes dropped)."""
    parts = [c.strip() for c in row.strip().split("|")]
    if parts and parts[0] == "":
        parts = parts[1:]
    if parts and parts[-1] == "":
        parts = parts[:-1]
    return parts


def find_row(md: str, first_col: str) -> "list[str]":
    for line in md.splitlines():
        c = cells(line)
        if c and c[0] == first_col:
            return c
    raise AssertionError(f"table row starting with {first_col!r} not found in:\n{md}")


# ---------------------------------------------------------------------------
# Smoke
# ---------------------------------------------------------------------------

def test_renders_both_pages() -> None:
    downloads, archive, proc = render()
    assert proc.returncode == 0, proc.stderr
    assert downloads.startswith("# Downloads"), downloads[:80]
    assert archive.startswith("# Downloads — All Releases"), archive[:80]


# ---------------------------------------------------------------------------
# Downloads.md — intro wording
# ---------------------------------------------------------------------------

def test_intro_drops_single_version_wording() -> None:
    downloads, _, _ = render()
    assert "Minecraft 1.21.1 mod" not in downloads
    assert "available for Minecraft 1.21.1 and 1.20.1" in downloads


# ---------------------------------------------------------------------------
# Downloads.md — latest-release matrix
# ---------------------------------------------------------------------------

def test_latest_matrix_has_both_mc_rows_in_descending_order() -> None:
    downloads, _, _ = render()
    assert "| Minecraft | Fabric | Forge | NeoForge |" in downloads
    idx_2111 = downloads.find("\n| 1.21.1 |")
    idx_2011 = downloads.find("\n| 1.20.1 |")
    assert idx_2111 != -1 and idx_2011 != -1, "both MC rows must be present"
    assert idx_2111 < idx_2011, "1.21.1 row should sort above 1.20.1"


def test_latest_matrix_1211_row_fills_all_three_loaders() -> None:
    downloads, _, _ = render()
    row = find_row(downloads, "1.21.1")
    # [mc, fabric, forge, neoforge]
    assert "adventureitemnames-fabric-0.46.0+1.21.1.jar" in row[1]
    assert "adventureitemnames-forge-0.46.0+1.21.1.jar" in row[2]
    assert "adventureitemnames-neoforge-0.46.0+1.21.1.jar" in row[3]


def test_latest_matrix_1201_row_has_empty_neoforge_cell() -> None:
    downloads, _, _ = render()
    row = find_row(downloads, "1.20.1")
    assert "adventureitemnames-fabric-0.46.0+1.20.1.jar" in row[1]
    assert "adventureitemnames-forge-0.46.0+1.20.1.jar" in row[2]
    assert row[3] == EMDASH, f"NeoForge 1.20.1 cell should be empty, got {row[3]!r}"


def test_latest_matrix_links_both_fabric_mc_jars() -> None:
    downloads, _, _ = render()
    # Both MC builds of the Fabric jar must be reachable — the bug this fixes.
    assert (
        "/download/v0.46.0/adventureitemnames-fabric-0.46.0+1.21.1.jar" in downloads
    )
    assert (
        "/download/v0.46.0/adventureitemnames-fabric-0.46.0+1.20.1.jar" in downloads
    )


# ---------------------------------------------------------------------------
# Downloads-Archive.md — per-version columns + legacy compatibility
# ---------------------------------------------------------------------------

def test_archive_has_per_version_columns() -> None:
    _, archive, _ = render()
    header = (
        "| Version | Released | Fabric 1.21.1 | Fabric 1.20.1 | "
        "Forge 1.21.1 | Forge 1.20.1 | NeoForge 1.21.1 | Release notes |"
    )
    assert header in archive, archive


def test_archive_latest_row_fills_full_grid() -> None:
    _, archive, _ = render()
    row = find_row(archive, "v0.46.0")
    # [version, date, fab2111, fab2011, forge2111, forge2011, neo2111, notes]
    assert "fabric-0.46.0+1.21.1.jar" in row[2]
    assert "fabric-0.46.0+1.20.1.jar" in row[3]
    assert "forge-0.46.0+1.21.1.jar" in row[4]
    assert "forge-0.46.0+1.20.1.jar" in row[5]
    assert "neoforge-0.46.0+1.21.1.jar" in row[6]


def test_archive_legacy_row_fills_only_1211_columns() -> None:
    _, archive, _ = render()
    row = find_row(archive, "v0.45.0")
    assert "fabric-0.45.0.jar" in row[2]      # Fabric 1.21.1
    assert row[3] == EMDASH                   # Fabric 1.20.1
    assert "forge-0.45.0.jar" in row[4]       # Forge 1.21.1
    assert row[5] == EMDASH                   # Forge 1.20.1
    assert "neoforge-0.45.0.jar" in row[6]    # NeoForge 1.21.1


def test_archive_prev020_single_jar_falls_back_to_neoforge() -> None:
    _, archive, _ = render()
    row = find_row(archive, "v0.1.0")
    assert row[2] == EMDASH                       # Fabric 1.21.1
    assert row[3] == EMDASH                       # Fabric 1.20.1
    assert row[4] == EMDASH                       # Forge 1.21.1
    assert row[5] == EMDASH                       # Forge 1.20.1
    assert "adventureitemnames-0.1.0.jar" in row[6]  # NeoForge 1.21.1 fallback


# ---------------------------------------------------------------------------
# Env-var guards
# ---------------------------------------------------------------------------

def test_missing_repo_fails() -> None:
    _, _, proc = render(REPO=None)
    assert proc.returncode != 0
    assert "REPO" in proc.stderr


def _run() -> None:
    fns = [v for k, v in sorted(globals().items()) if k.startswith("test_")]
    failures = 0
    for fn in fns:
        try:
            fn()
            print(f"PASS {fn.__name__}")
        except AssertionError as e:
            failures += 1
            print(f"FAIL {fn.__name__}: {e}")
    print(f"\n{len(fns) - failures}/{len(fns)} passed")
    raise SystemExit(1 if failures else 0)


if __name__ == "__main__":
    _run()
