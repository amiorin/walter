"""Command-line entry point."""
from __future__ import annotations

import sys

from big_config.core import Opts
from once import tools as once_tools

from .options import bb
from .package import walter_star
from .params import walter_opts
from .tools import ansible_star

HELP = """Usage: walter <command> [args...]

Commands:
  package <step>...       Run Walter package workflow steps for the active profile.
                            walter package validate
                            walter package describe
                            walter package build
                            walter package create
                            walter package delete
                            walter package git-check lock build unlock-any

  Package steps:
    validate              Pre-flight profile, tool, credential, and Ansible-data checks.
    describe              Providers, compute reachability, and workstation summary report.
    build                 Render Walter stages without applying/provisioning.
    create                Provision and configure the Walter workstation.
    delete                Destroy the compute Tofu stage.
    lock                  Acquire the BigConfig Git-tag lock.
    git-check             Verify the Git working tree/upstream state is clean.
    git-push              Run git push through the BigConfig workflow.
    unlock-any            Force-release the computed BigConfig lock tag.

  Individual tools (accept SDK workflow steps and exec commands):
  tofu <args>             e.g. walter tofu render tofu:init tofu:apply:-auto-approve
                          e.g. walter tofu git-check lock render tofu:init tofu:plan unlock-any
  ansible <args>          e.g. walter ansible render -- ansible-playbook main.yml
  ansible-local <args>    e.g. walter ansible-local render -- ansible-playbook main.yml

Notes:
  * When launched through `run`, the active profile comes from that script;
    otherwise it defaults to `bb` in walter/options.py.
  * Any param can be overridden with BC_PAR_* environment variables."""

PACKAGE_COMMANDS = {"validate", "describe", "build", "create", "delete", "lock", "git-check", "git-push", "unlock-any"}


def die(*lines: str) -> None:
    for line in lines:
        print(line, file=sys.stderr)
    raise SystemExit(1)


def main(argv: list[str] | None = None, opts: Opts | None = None) -> None:
    argv = list(sys.argv[1:] if argv is None else argv)
    if argv[:1] == ["--"]:
        argv = argv[1:]
    active_profile = opts if opts is not None else bb
    command = argv[0] if argv else None
    rest = argv[1:] if argv else []

    if command in {None, "help", "--help", "-h"}:
        print(HELP)
        return
    if command == "package":
        if rest and rest[0] in {"help", "--help", "-h"}:
            print(HELP)
            return
        if rest:
            walter_star(rest, active_profile)
            return
        die("Missing package step.", "Usage: walter package <step>...")
    if command in PACKAGE_COMMANDS:
        die(f"Use `walter package {command}`.", "", HELP)
    if command == "tofu":
        once_tools.tofu_star(rest, walter_opts(active_profile))
        return
    if command == "ansible":
        ansible_star(rest, walter_opts(active_profile))
        return
    if command == "ansible-local":
        once_tools.ansible_local_star(rest, walter_opts(active_profile))
        return

    die(f"Unknown command: {command}", "", HELP)


if __name__ == "__main__":
    main()
