"""Compares a mod client's reported version against the latest published one.

The comparison lives here rather than in the mod on purpose: a mistake in it can
be fixed by redeploying the server, whereas a client-side comparator would need
the very mod update it is supposed to announce.

Version strings look like ``0.1.0-Beta+26.2`` — ``<mod_version>+<build_target>``,
where the build target is the branch's Minecraft/artifact version and may carry a
``-open`` suffix for the unrestricted build. Only the mod version is compared:
the four release branches ship the same mod version against different build
targets, so a 1.21.10 client must not be judged against a "26.2" string.
"""
import re

from . import config, database

_NUMERIC_RE = re.compile(r"^\d+$")

# DB-backed so an admin can change them from the GUI without a redeploy; the
# GM_LATEST_MOD_VERSION / GM_MOD_DOWNLOAD_URL env vars only seed the initial
# value (see database.get_setting's default param).


def latest_version() -> str:
    return database.get_setting("latest_mod_version", config.LATEST_MOD_VERSION).strip()


def download_url() -> str:
    return database.get_setting("mod_download_url", config.MOD_DOWNLOAD_URL).strip()


def split_version(raw: str | None) -> tuple[str, str]:
    """``"0.1.0-Beta+26.2"`` -> ``("0.1.0-Beta", "26.2")``. Missing parts are ""."""
    text = (raw or "").strip()
    if not text:
        return "", ""
    mod_version, _, build_target = text.partition("+")
    return mod_version.strip(), build_target.strip()


def sort_key(mod_version: str) -> tuple:
    """A comparable key for a mod version, following semver's pre-release rule.

    Numeric dot-separated parts compare numerically; a trailing pre-release tag
    (``0.1.0-Beta``) sorts *below* the same version without one, so 0.1.0 is
    newer than 0.1.0-Beta. Non-numeric junk degrades to a 0 part rather than
    raising, so a malformed client version can never break a connection.
    """
    core, _, prerelease = mod_version.partition("-")
    numbers = []
    for part in core.split("."):
        numbers.append(int(part) if _NUMERIC_RE.match(part) else 0)
    # Pad so 0.1 and 0.1.0 compare equal.
    while len(numbers) < 3:
        numbers.append(0)
    # has_no_prerelease: False (0) sorts before True (1), so a release wins.
    return (tuple(numbers), bool(not prerelease), prerelease.lower())


def is_outdated(client_version: str, latest_version: str) -> bool:
    """True only when the client is strictly older — never when it is ahead.

    A developer running an unreleased build should not be nagged, so "different"
    is not treated as "outdated".
    """
    if not client_version or not latest_version:
        return False
    return sort_key(client_version) < sort_key(latest_version)


def update_notice(raw_client_version: str | None) -> dict | None:
    """The ``UPDATE_AVAILABLE`` payload for this client, or None if it is current.

    Returns None when no latest version is configured, so leaving
    ``GM_LATEST_MOD_VERSION`` unset switches notifications off entirely.
    """
    latest = latest_version()
    if not latest:
        return None

    # The build target is dropped: it exists only to be kept out of the comparison
    # (see split_version), and the download link is the same for every branch.
    client_mod_version, _ = split_version(raw_client_version)
    if not is_outdated(client_mod_version, latest):
        return None

    notice = {
        "currentVersion": client_mod_version,
        "latestVersion": latest,
    }
    url = download_url()
    if url:
        notice["downloadUrl"] = url
    return notice
