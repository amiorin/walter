# CLAUDE.md

This file describes the Walter TypeScript codebase for AI assistants. Read it before making changes.

## Project Overview

Walter provisions a cloud VM (or targets an existing no-infra host through Once's shared Tofu templates) and configures it as a development workstation with Ansible. This leaf is the TypeScript implementation and mirrors `../clojure` and `../python`.

It depends on the TypeScript `once` package for shared `tofu`, `ansible-local`, and Tofu-output parameter extraction, and on `big-config` for workflow/rendering.

## Commands

```bash
npm install
npm run typecheck
npm test
npm run build
npm run walter -- help
npm run walter -- package validate
npm run walter -- package build
node run package build
```

Individual tools:

```bash
npm run walter -- tofu render
npm run walter -- ansible render -- ansible-playbook main.yml
npm run walter -- ansible-local render -- ansible-playbook main.yml
```

Do not run `package create`, `package delete`, `tofu:apply`, or `tofu:destroy` unless explicitly approved.

## Architecture

- `src/cli.ts` — CLI entry point.
- `src/walter/package.ts` — package workflow: Once `tofu` -> Walter `ansible` -> Once `ansible-local`.
- `src/walter/tools.ts` — Walter-owned remote Ansible render workflow.
- `src/walter/ansible.ts` — pure Ansible data and generated YAML/JSON render functions.
- `src/walter/params.ts` — `BC_PAR_*` overrides + Once Tofu output params.
- `src/walter/validation.ts` — schema/tool/credential/Ansible-data validation.

Keep generated `.dist/` out of source and preserve kebab-case parameter keys.

## Parity

`package build` must be byte-for-byte compatible with the Clojure reference artifact under `../clojure/.dist/walter-7b467017/`.

## Git

Stay on the `typescript` branch. Do not commit unless explicitly asked.
