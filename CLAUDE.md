# CLAUDE.md

This file describes the Walter Python codebase for AI assistants. Read it before making changes.

## Project Overview

Walter provisions a cloud VM (or targets an existing no-infra host through Once's shared Tofu templates) and configures it as a development workstation with Ansible. This leaf is the Python implementation and mirrors `../clojure` and `../typescript`.

It depends on the Python `once` package for shared `tofu`, `ansible-local`, and Tofu-output parameter extraction, and on `big-config` for workflow/rendering.

## Commands

```bash
uv sync
uv run pytest -q
uv run walter -- help
uv run walter -- package validate
uv run walter -- package build
uv run python run package build
```

Individual tools:

```bash
uv run walter -- tofu render
uv run walter -- ansible render -- ansible-playbook main.yml
uv run walter -- ansible-local render -- ansible-playbook main.yml
```

Do not run `package create`, `package delete`, `tofu:apply`, or `tofu:destroy` unless explicitly approved.

## Architecture

- `src/walter/cli.py` — CLI entry point.
- `src/walter/package.py` — package workflow: Once `tofu` -> Walter `ansible` -> Once `ansible-local`.
- `src/walter/tools.py` — Walter-owned remote Ansible render workflow.
- `src/walter/ansible.py` — pure Ansible data and generated YAML/JSON render functions.
- `src/walter/params.py` — `BC_PAR_*` overrides + Once Tofu output params.
- `src/walter/validation.py` — schema/tool/credential/Ansible-data validation.

Keep generated `.dist/` out of source and preserve kebab-case parameter keys.

## Parity

`package build` must be byte-for-byte compatible with the Clojure reference artifact under `../clojure/.dist/walter-7b467017/`.

## Git

Stay on the `python` branch. Do not commit unless explicitly asked.
