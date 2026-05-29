# CLAUDE.md

This file provides guidance to coding agents when working in this repository.

## What Walter Does

Walter is an infrastructure automation tool that provisions cloud VMs (Hetzner Cloud, OCI, DigitalOcean, or an existing no-infra host via the shared Once Tofu templates) and configures them as development environments. It orchestrates **OpenTofu** (infrastructure) and **Ansible** (configuration) via **Clojure/Babashka**.

## Commands

The canonical CLI runs through the root Babashka `run` script:

```bash
bb run package build                       # Render all stages without applying/provisioning
bb run package create                      # Full workflow: tofu + ansible + ansible-local
bb run package delete                      # Destroy the compute Tofu stage
bb run tofu render                         # Render OpenTofu templates to .dist/
bb run tofu tofu:init                      # Initialize OpenTofu
bb run tofu tofu:plan                      # Preview infrastructure changes
bb run tofu tofu:apply                     # Apply infrastructure
bb run tofu tofu:destroy                   # Teardown infrastructure
bb run ansible render                      # Render Ansible playbooks/inventory
bb run ansible -- ansible-playbook main.yml       # Run Ansible playbook against remote host
bb run ansible-local -- ansible-playbook main.yml # Run Ansible tasks locally
```

Run tests:
```bash
clojure -M:test
```

Code maintenance:
```bash
clojure-lsp clean-ns
clojure-lsp format
```

## Architecture

The project uses [big-config](https://github.com/bigconfig-ai/big-config) as its workflow engine. The pattern throughout is:
- `*` suffix functions (e.g. `ansible*`, `walter*`) are CLI/REPL entry points — they parse CLI args and call the non-starred variant
- Workflows are composed as `step-fns` pipelines using `big-config.workflow`
- Templates are rendered from `src/resources/` to `.dist/` before being executed

**Key namespaces:**
- `io.github.bigconfig-ai.walter.cli` — `bb run ...` command parser
- `io.github.bigconfig-ai.walter.package` — top-level `walter` workflow, orchestrates `tofu` → `ansible` → `ansible-local`
- `io.github.bigconfig-ai.walter.tools` — Walter-specific `ansible` workflow definition
- `io.github.bigconfig-ai.walter.ansible` — data generation for Ansible: users, packages (devbox), repos, SSH config, inventory
- `io.github.bigconfig-ai.walter.options` — static config; `bb` is the fallback profile when no `run` profile is supplied
- `io.github.bigconfig-ai.walter.params` — composes `opts-fn` / `walter-opts` for reading big-config params

**External dependencies:**
- `io.github.bigconig-ai/once` — provides shared `tofu*` and `ansible-local*` tooling
- `io.github.amiorin/big-config` — workflow engine, template rendering, step functions (pinned to the `bigconfig-ai/big-config` Git repo by URL)

## REPL Development

Use `(debug tap-values ...)` in `comment` blocks (as shown in the source files) to inspect intermediate workflow state. Start with `:dev` alias:

```bash
clojure -A:dev
```

## Configuration

- The root `run` file contains safe placeholder defaults; override real values with `BC_PAR_*` environment variables or an explicit opts map in REPL use.
- Modify `src/clj/io/github/bigconfig_ai/walter/ansible.clj` (`data-fn`) to change packages, repos, or users provisioned on the remote box.
- Walter-owned templates live under `src/resources/io/github/bigconfig-ai/walter/tools/`.
- Requires `HCLOUD_TOKEN` env var for Hetzner Cloud, or OCI CLI configured for Oracle, when using those providers.
- `.dist/` is generated — do not edit files there directly.
