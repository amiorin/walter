# Walter (Python)

Walter provisions cloud instances (Hetzner Cloud, OCI, DigitalOcean, or an existing `no-infra` host through the shared Once Tofu templates) and configures them as a personalized development environment. It combines **OpenTofu** for infrastructure and **Ansible** for configuration, orchestrated through the Python SDK (the [`big-config`](https://github.com/bigconfig-ai/big-config) package).

This is the Python implementation. It depends on the Python [`once`](https://github.com/bigconfig-ai/once) package for the shared `tofu` and `ansible-local` stages and on the Python SDK (`big-config`) for the workflow engine and renderer (both pinned to GitHub commits in `pyproject.toml`). It mirrors `../clojure` and `../typescript`.

## Features

- Provision compute using the shared Once OpenTofu templates.
- Configure users, SSH, shell tooling, and system services through Walter-specific Ansible roles.
- Install common development tools through `devbox`.
- Clone and prepare project repositories/worktrees.
- Run the whole lifecycle through a launcher-friendly CLI.

## Prerequisites

- [Python](https://www.python.org/) 3.12+ and [`uv`](https://docs.astral.sh/uv/)
- [OpenTofu](https://opentofu.org/)
- [Ansible](https://www.ansible.com/)
- Cloud credentials for the selected provider (e.g. `HCLOUD_TOKEN`), or existing host details for `no-infra`

## Install

```bash
uv sync
```

## Usage

The root `run` script contains safe placeholder defaults. Override parameters with `BC_PAR_*` environment variables, for example:

```bash
export BC_PAR_PROVIDER_COMPUTE=no-infra
export BC_PAR_COMPUTE_PUBKEY="$(cat ~/.ssh/id_ed25519.pub)"
export BC_PAR_NO_INFRA_COMPUTE_IP=203.0.113.10
export BC_PAR_NO_INFRA_COMPUTE_USER=ubuntu
export BC_PAR_NO_INFRA_COMPUTE_SUDOER=root
```

### Package workflow

```bash
uv run walter -- package validate # validate params, tools, credentials, and Ansible data
uv run walter -- package build    # render all stages without applying/provisioning
uv run walter -- package create   # tofu -> ansible -> ansible-local
uv run walter -- package delete   # destroy the compute Tofu stage
```

The root `run` script is the launcher-friendly entry point (it supplies the default profile):

```bash
uv run python run package build
```

### Individual tools

```bash
uv run walter -- tofu render
uv run walter -- tofu tofu:init
uv run walter -- tofu tofu:plan
uv run walter -- tofu tofu:apply
uv run walter -- tofu tofu:destroy

uv run walter -- ansible render
uv run walter -- ansible -- ansible-playbook main.yml

uv run walter -- ansible-local render
uv run walter -- ansible-local -- ansible-playbook main.yml
```

## Development

```bash
uv sync
uv run pytest -q
uv run walter -- help
```

`package build` is verified byte-for-byte against the Clojure reference artifact under `../clojure/.dist/walter-7b467017/`.

## Customization

- `src/walter/ansible.py` defines users, packages, repositories, SSH config, and generated Ansible data.
- `src/walter/options.py` contains the fallback profile used when no profile is supplied by `run`.
- `src/walter/params.py` composes `BC_PAR_*` overrides with Once Tofu-output params.
- `src/walter/validation.py` validates the profile schema, tools, credentials, and Ansible data.
- Walter-owned templates live under `src/resources/io/github/bigconfig-ai/walter/tools/`.

`.dist/` is generated output. Do not run provisioning/destructive commands without real credentials and explicit intent, and do not edit `.dist/` directly.

## Launcher

Walter is shaped as a Python BigConfig package and is intended to be consumable with `bc-pkg` from `bigconfig-ai/walter@python`.

## License

Copyright © 2026 Alberto Miorin.

Distributed under the MIT License.
