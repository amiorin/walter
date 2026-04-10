# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What Walter Does

Walter is an infrastructure automation tool that provisions cloud VMs (Hetzner Cloud or OCI) and configures them as development environments. It orchestrates **OpenTofu** (infrastructure) and **Ansible** (configuration) via **Clojure/Babashka**.

## Commands

All tasks run via Babashka (`bb`):

```bash
bb walter create                           # Full workflow: tofu + ansible + ansible-local
bb tofu render                             # Render OpenTofu templates to .dist/
bb tofu tofu:init                          # Initialize OpenTofu
bb tofu tofu:plan                          # Preview infrastructure changes
bb tofu tofu:apply                         # Apply infrastructure
bb tofu tofu:destroy                       # Teardown infrastructure
bb ansible render                          # Render Ansible playbooks/inventory
bb ansible ansible-playbook:main.yml       # Run Ansible playbook against remote host
bb ansible-local ansible-playbook:main.yml # Run Ansible tasks locally
bb tidy                                    # Format and clean-ns via clojure-lsp
```

Run tests:
```bash
clojure -X:test
```

## Architecture

The project uses [big-config](https://github.com/amiorin/big-config) as its workflow engine. The pattern throughout is:
- `*` suffix functions (e.g. `ansible*`, `walter*`) are the Babashka entry points — they parse CLI args and call the non-starred variant
- Workflows are composed as `step-fns` pipelines using `big-config.workflow`
- Templates are rendered from `src/resources/` to `.dist/` before being executed

**Key namespaces:**
- `io.github.amiorin.walter.package` — top-level `walter` workflow, orchestrates `tofu` → `ansible` → `ansible-local`
- `io.github.amiorin.walter.tools` — `ansible` workflow definition
- `io.github.amiorin.walter.ansible` — data generation for Ansible: users, packages (devbox), repos, SSH config, inventory
- `io.github.amiorin.walter.options` — static config for OCI and S3 backend; `bb` is an alias for `walter` opts
- `io.github.amiorin.walter.params` — composes `opts-fn` / `walter-opts` for reading big-config params

**External dependencies from sibling repos:**
- `io.github.amiorin/once` — provides `tools-once/tofu*` and `tools-once/ansible-local*`
- `io.github.amiorin/big-config` — workflow engine, template rendering, step functions

## REPL Development

The `env/dev/clj/user.clj` sets up a `debug-atom` tap. Use `(debug tap-values ...)` in `comment` blocks (as shown in the source files) to inspect intermediate workflow state. Start with `:dev` alias:

```bash
clojure -A:dev
```

## Configuration

- Modify `src/clj/io/github/amiorin/walter/ansible.clj` (`data-fn`) to change packages, repos, or users provisioned on the remote box.
- `src/clj/io/github/amiorin/walter/options.clj` holds OCI and S3 backend coordinates.
- Requires `HCLOUD_TOKEN` env var for Hetzner Cloud, or OCI CLI configured for Oracle.
- `.dist/` is generated — do not edit files there directly.
