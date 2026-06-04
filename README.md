# Walter

Walter provisions cloud instances (or targets an existing host) and configures them as a personalized development environment. It combines **OpenTofu** for infrastructure and **Ansible** for configuration, orchestrated through **Clojure** and **Babashka** on top of the Clojure SDK (the [`big-config`](https://github.com/bigconfig-ai/big-config) package).

## Features

- Provision compute using the shared Once OpenTofu templates.
- Configure users, SSH, shell tooling, and system services through Walter-specific Ansible roles.
- Install common development tools through `devbox`.
- Clone and prepare project repositories/worktrees.
- Run the whole lifecycle through a launcher-friendly `bb run ...` CLI.

## Prerequisites

- [Babashka](https://babashka.org/)
- [Clojure](https://clojure.org/)
- [OpenTofu](https://opentofu.org/)
- [Ansible](https://www.ansible.com/)
- Cloud credentials for the selected provider, or existing host details for `no-infra`

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
bb run package validate # validate params, tools, credentials, and Ansible data
bb run package build    # render all stages without applying/provisioning
bb run package create   # tofu -> ansible -> ansible-local
bb run package delete   # destroy the compute Tofu stage
```

### Individual tools

```bash
bb run tofu render
bb run tofu tofu:init
bb run tofu tofu:plan
bb run tofu tofu:apply
bb run tofu tofu:destroy

bb run ansible render
bb run ansible -- ansible-playbook main.yml

bb run ansible-local render
bb run ansible-local -- ansible-playbook main.yml
```

## Customization

- `src/clj/io/github/bigconfig_ai/walter/ansible.clj` defines users, packages, repositories, SSH config, and generated Ansible data.
- `src/clj/io/github/bigconfig_ai/walter/options.clj` contains the fallback profile used when no profile is supplied by `run`.
- `src/resources/io/github/bigconfig-ai/walter/tools/` contains Walter-owned templates and Ansible roles.

`.dist/` is generated output and should not be edited directly.

## Launcher

Walter is shaped as a Clojure BigConfig package and is intended to be consumable with `bc-pkg` from `bigconfig-ai/walter@clojure`.

## License

Copyright © 2026 Alberto Miorin.

Distributed under the MIT License.
