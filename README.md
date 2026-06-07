# Walter (TypeScript)

Walter provisions cloud instances (Hetzner Cloud, OCI, DigitalOcean, or an existing `no-infra` host through the shared Once Tofu templates) and configures them as a personalized development environment. It combines **OpenTofu** for infrastructure and **Ansible** for configuration, orchestrated through the TypeScript SDK (the [`big-config`](https://github.com/bigconfig-ai/big-config) package).

This is the TypeScript implementation. It depends on the TypeScript [`once`](https://github.com/bigconfig-ai/once) package for the shared `tofu` and `ansible-local` stages and on the TypeScript SDK (`big-config`) for the workflow engine and renderer (both pinned to GitHub commits in `package.json`). It mirrors `../clojure` and `../python`.

## Features

- Provision compute using the shared Once OpenTofu templates.
- Configure users, SSH, shell tooling, and system services through Walter-specific Ansible roles.
- Install common development tools through `devbox`.
- Clone and prepare project repositories/worktrees.
- Run the whole lifecycle through a launcher-friendly CLI.

## Prerequisites

- [Node.js](https://nodejs.org/) 20+
- [OpenTofu](https://opentofu.org/)
- [Ansible](https://www.ansible.com/)
- Cloud credentials for the selected provider (e.g. `HCLOUD_TOKEN`), or existing host details for `no-infra`

## Install

```bash
npm install
npm run build
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
npm run walter -- package validate # validate params, tools, credentials, and Ansible data
npm run walter -- package describe # providers, compute reachability, workstation summary
npm run walter -- package build    # render all stages without applying/provisioning
npm run walter -- package create   # tofu -> ansible -> ansible-local
npm run walter -- package delete   # destroy the compute Tofu stage
npm run walter -- package git-check lock build unlock-any # advanced Git/lock workflow helpers
```

The root `run` script is the launcher-friendly entry point (it supplies the default profile); after `npm run build`:

```bash
node run package build
```

### Individual tools

```bash
npm run walter -- tofu render
npm run walter -- tofu git-check lock render tofu:init tofu:plan unlock-any
npm run walter -- tofu tofu:init
npm run walter -- tofu tofu:plan
npm run walter -- tofu tofu:apply
npm run walter -- tofu tofu:destroy

npm run walter -- ansible render
npm run walter -- ansible -- ansible-playbook main.yml

npm run walter -- ansible-local render
npm run walter -- ansible-local -- ansible-playbook main.yml
```

## Development

```bash
npm install
npm run typecheck   # tsc --noEmit
npm test            # vitest run
npm run build       # compile to dist/
npm run walter -- help
```

`package build` is verified byte-for-byte against the Clojure reference artifact under `../clojure/.dist/walter-7b467017/`.

## Customization

- `src/walter/ansible.ts` defines users, packages, repositories, SSH config, and generated Ansible data.
- `src/walter/package.ts` composes the package workflow: Once `tofu` -> Walter `ansible` -> Once `ansible-local`.
- `src/walter/tools.ts` defines the Walter-owned remote Ansible render workflow.
- `src/walter/params.ts` composes `BC_PAR_*` overrides with Once Tofu-output params.
- `src/walter/validation.ts` validates the profile schema, tools, credentials, and Ansible data.
- Walter-owned templates live under `src/resources/io/github/bigconfig-ai/walter/tools/`.

`.dist/` is generated output. Do not run provisioning/destructive commands without real credentials and explicit intent, and do not edit `.dist/` directly.

## Launcher

Walter is shaped as a TypeScript BigConfig package and is intended to be consumable with `bc-pkg` from `bigconfig-ai/walter@typescript`.

## License

Copyright © 2026 Alberto Miorin.

Distributed under the MIT License.
