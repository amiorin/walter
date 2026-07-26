# CLAUDE.md

This file describes the `walter` codebase for AI assistants. Read it before making changes.

## Project Overview

`walter` provisions a cloud VM (or targets an existing host) and configures it as a development workstation, with [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/).

It is built on [`green`](https://github.com/amiorin/green), a DAG workflow engine: a graph of steps threaded by an `opts` map, with advice (before/after/around) attached per step. This branch is a rewrite — the BigConfig SDK, `bb run package …`, `options.clj` profiles, and `BC_PAR_*` variables are gone. Do not reintroduce those concepts.

Walter reuses [`once`](https://github.com/bigconfig-ai/once) for the two stages the two packages share, and owns only the Ansible run that builds the workstation.

The repository ships two things from one file:

- **The launcher** `green-walter/green`, a single Babashka script. `./green` in the repository root is a symlink to it. It holds no logic of its own: validation, the graph, and the steps live in the library, where tests cover them. What remains is the part that cannot live there — resolving the library in the first place.
- **The `green-walter` skill** (`green-walter/SKILL.md` + `references/configuration.md`), whose payload is that same launcher, copied into a user's own project. Standing alone it resolves `walter`, `once` and `green` as pinned git dependencies; inside this repository `bb.edn` supplies local roots and the bootstrap is skipped.

## Tech Stack

- **Language**: Clojure 1.12.5 (JVM), plus Babashka for the launcher
- **Workflow engine**: `io.github.amiorin/green` (`green.workflow`, `green.scaffold`, `green.tofu`, `green.ansible`, `green.cli`, `green.progress`, `green.dry-run`)
- **Shared stages**: `io.github.bigconfig-ai/once` (compute, ansible-local, the small YAML emitter)
- **Infrastructure**: OpenTofu; **Config management**: Ansible
- **Dev environment**: Nix via `devenv` + `direnv`

## Repository Structure

```
walter/
├── green-walter/
│   ├── green                # THE launcher: bootstrap, validation, workflow, CLI, pin
│   ├── SKILL.md             # green-walter skill definition
│   └── references/
│       └── configuration.md # desired-state reference the skill reads before generating green.edn
├── green                    # symlink -> green-walter/green
├── green.edn                # desired state for this repository's own workstation
├── src/
│   ├── clj/io/github/bigconfig_ai/walter/
│   │   ├── workflow.clj     # the DAG, start-step, ansible-cleanup, backend advice
│   │   ├── validate.clj     # the provider registry (Once's, narrowed) and desired-state checks
│   │   ├── tools.clj        # the ansible-remote stage, template specs, generated data
│   │   ├── describe.clj     # post-provisioning report (providers, compute status, workstation)
│   │   └── utils.clj        # the contract number
│   └── resources/io/github/bigconfig-ai/walter/
│       ├── raw              # `<{ content|safe }>` — the template used for verbatim content
│       └── tools/ansible/   # the workstation play: roles/root, roles/users
├── test/clj/io/github/bigconfig_ai/walter/
│   ├── workflow_test.clj    # start gating, the graph, backends, a whole build
│   ├── validate_test.clj    # the registry, desired-state and secret errors
│   ├── tools_test.clj       # data shaping, generated files, scaffolding
│   └── describe_test.clj    # report classification and assembly
├── tasks/pin.clj            # `bb pin` — maintainers only, not a launcher subcommand
├── index.html               # the user-facing manual for the green-walter skill
├── deps.edn / bb.edn        # git-pinned green + once; bb.edn overrides with local roots
├── plans/                   # historical task briefs predating the rewrite — not authoritative
└── devenv.nix / .envrc      # Nix dev shell; .envrc sources .envrc.private (gitignored)
```

## Development Commands

```bash
bb green build                 # render <workdir>/<profile>/ only, no tofu, no ansible
bb green create                # provision and configure
bb green create --dry-run      # print the DAG actions, touch nothing
bb green delete                # destroy the compute stage
bb green describe              # providers, compute status, workstation summary

bb green build -f laptop.edn   # -f/--file selects a desired-state file (default: ./green.edn)

bb pin                         # stamp the launcher's pins — maintainers only

clojure -M:test                # cognitect test-runner over test/clj
clojure-lsp clean-ns && clojure-lsp format
clj-kondo --lint src/clj test/clj tasks green-walter/green
```

## Desired state (`green.edn`)

A single flat EDN map, except for the nested `:walter` collection. Provider selection and non-secret settings live here; credentials never do.

```clojure
{:profile "walter"              ; names the workdir, the state keys, the compute
 :workdir ".green"              ; resource, and the ~/.ssh/config Host alias
 :compute-pubkey "ssh-ed25519 AAAA... me@example.com"
 :walter {:tailnet "example-tailnet.ts.net"
          :users [{:name "ubuntu" :doomemacs "<sha>" :remove false}]
          :repos [{:user "ubuntu" :org "acme" :repo "site" :branch "main" :worktrees []}]
          :packages [["ripgrep" "rg"] ["fish" "fish"]]}
 :provider-compute "oci"           ; digitalocean | hcloud | oci | no-infra
 :provider-backend "s3"            ; local | s3 | r2
 :compute-prevent-destroy true}
```

Load-bearing rules:

- **The workstation is desired state, not source.** Users, repos, packages and the tailnet live under `:walter`. `tools/data-fn` reads them; nothing is hardcoded in the namespaces.
- **`:packages` entries are `[package cli]`.** The play checks for the CLI on PATH, so a package whose binary is named differently needs both, as `ripgrep`/`rg` does.
- **A user that names no `:uid` inherits the compute stage's `uid` output**, because the play creates the user by uid and a clash with the image's default user would fail. Naming a `:uid` overrides that.
- **`GREEN_PAR_*` is the only secret channel.** `green.cli/read-pars` overlays any such variable onto the matching flat key — uppercased, hyphens as underscores, so `:hcloud-token` ← `GREEN_PAR_HCLOUD_TOKEN`. Overrides are coerced to the type of the value they replace, so `GREEN_PAR_COMPUTE_PREVENT_DESTROY=false` stays a boolean. Any flat key can be overridden the same way. There is no `TF_VAR_*` and no second mechanism.
- **`:compute-pubkey` is required.** It is authorized for every user the play creates, and ssh-agent must hold it when a cloud compute provider builds the box.

## Architecture

### The DAG

`wire-fn` in `walter.workflow` returns `[step-fn & next-steps]` per step and switches on `:green/event`. Create and build:

```text
start ─ tofu-compute ─┬─ ansible-local
                      └─ ansible-remote
```

Delete:

```text
start ─ ansible-cleanup ─ tofu-compute
```

The two Ansible stages are independent and run concurrently. Walter has one Tofu stage and therefore no join, which is the main way its graph is simpler than Once's.

`workflow` also attaches: backend advice `:before` the Tofu step, `progress/advise` (the `>>> / <<<` lines), and `dry-run/advise` over `side-effecting-steps` (so `--dry-run` skips them).

### The opts map

One map is threaded through every step. Reserved keys are namespaced; desired-state keys are plain kebab-case keywords.

| Key | Meaning |
|---|---|
| `:green/exit` | 0 success, >0 failure — how steps report, instead of throwing |
| `:green/err`, `:green/trace` | failure message and stack trace |
| `:green/event` | `:build`, `:create`, or `:delete`, stamped by `green.cli` |
| `:green/dry-run` | set by `--dry-run` |
| `:once/compute-params` | the compute stage's outputs, also merged to the top level |
| `:green.scaffold/written`, `:green.scaffold/deleted` | paths a scaffold touched |

### Stages

Each stage owns an isolated directory, `tools/tool-dir` = `<workdir>/<profile>/<tool>` — the same layout Once uses, so both packages' stages sit side by side.

| Step | Work dir | Owner | Does |
|---|---|---|---|
| `:walter/tofu-compute` | `tofu-compute` | Once | provisions the VM (or passes through `no-infra`), outputs ip/user/sudoer/name |
| `:walter/ansible-local` | `ansible-local` | Once | writes the managed `Host <profile>` block into `~/.ssh/config` |
| `:walter/ansible-remote` | `ansible-remote` | Walter | installs nix, devbox, asdf, doomemacs; creates users; clones repos |

`tools/tofu-compute-step` wraps Once's compute step to merge its outputs into the top level. Once does that at its DNS join; Walter has no join, so the Ansible stages that follow read `ip`, `sudoer` and `uid` from there.

### Rendering

`green.scaffold` maps a qualified keyword to a classpath resource and renders it with Selmer, one spec per file — there is no directory-tree copy.

Walter's Ansible tree carries **no Selmer variables**: everything that varies is generated. So the twelve static files are copied verbatim through `raw-spec`, which renders the one-line `raw` template (`<{ content|safe }>`) with the resource's contents as data. That also sidesteps `roles/users/files/xterm-ghostty`, terminfo source containing `%<%t` and `\E[<%i` — sequences Selmer's `<% %>` tag delimiters would try to parse.

Five files are generated from desired state: `inventory.json`, `default.config.yml`, and `roles/users/tasks/{packages,repos,ssh-config}.yml`. `backend.tf.json` is the exception — `green.tofu` writes it directly from the backend advice, outside the scaffold.

A `build` of the reference `green.edn` produces exactly:

```text
<workdir>/<profile>/
├── tofu-compute/     backend.tf.json  main.tf
├── ansible-local/    ansible.cfg  inventory.ini  main.yml
└── ansible-remote/   ansible.cfg  main.yml  inventory.json  default.config.yml
                      roles/root/{files,handlers,tasks}  roles/users/{files,tasks}
```

### Parameter flow

1. `green.cli` reads the desired-state file and stamps `:green/event`.
2. `start-step` overlays `GREEN_PAR_*`, then validates (`state-errors`, and `secret-errors` for real create/delete).
3. The compute stage parses its `params` output into `:once/compute-params` and merges it to the top level. Once's fallback map stands in for `build` and dry-run so rendering never needs state.
4. Delete cannot re-derive those values, so `adopt-existing-state` reads the already-applied outputs back out of Tofu state before teardown.

### Secrets

Nothing lands in a rendered file:

- **OpenTofu**: the `:tofu-env` entry in Once's provider registry maps flat keys to the variables each provider reads natively (`:hcloud-token` → `HCLOUD_TOKEN`, …), and Once's compute step passes them through the process environment. Unset credentials are omitted, so build and dry-run stay credential-free.
- **State backends**: R2 authenticates through `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`; naming them in `backend.tf.json` would also write them to `.terraform/terraform.tfstate`.
- **Ansible**: `atuin_login` is emitted as a play-time `lookup('ansible.builtin.env', …)`, so it is resolved when the play runs rather than when the file is rendered.

### Backends

`backend-advice` writes `backend.tf.json` before the Tofu step: local, S3, or R2 as an S3-compatible backend with `region = "auto"`. Remote state keys are `<profile>/<tool>.tfstate`.

### Delete semantics

Deleting has to render before it can destroy: `tofu-with-spec` and `ansible-local-step` scaffold with `:green/event :create`, run the tool, and only then scaffold with `:delete` to remove the rendered tree. `ansible-cleanup-step` replays `ansible-local` so the managed `~/.ssh/config` block is dropped, then removes the `ansible-remote` tree. `:compute-prevent-destroy` defaults to `true`; a real delete refuses to start until `GREEN_PAR_COMPUTE_PREVENT_DESTROY=false`.

## The contract number and `bb pin`

`utils/contract` and `launcher-contract` in the launcher are a compatibility handshake. A standalone launcher refuses to run when the `walter` it resolved reports a lower contract, and says to update the skill instead of silently rendering from an older commit.

**Bump `utils/contract` (and `launcher-contract` to match) on any change a launcher pinned to an older commit could not survive** — a changed template variable, a renamed desired-state key, a new namespace the launcher requires. Then, after committing and pushing: `bb pin` stamps `walter-sha` (and `once-sha` / `green-sha`, when `ONCE_LIB_ROOT` / `GREEN_LIB_ROOT` point at checkouts) and the result is committed as `fix: re-pin bundled launcher to walter <sha>`. `pin` refuses to run on a dirty tree or an unpushed HEAD, and the pins are marked *managed — do not edit by hand*.

`pin` is a bb task rather than a launcher subcommand on purpose: it reads the HEAD of the checkout it runs in, so a payload copied into a stranger's project would stamp an unrelated SHA. A command that is wrong by construction there should not ship with the payload.

The launcher also `require`s `walter.describe` and `walter.workflow` defensively, capturing any load failure. A pin old enough to predate one of those namespaces would otherwise die with a bare "could not locate" message that says nothing about what to do; the contract check answers instead.

## Code Conventions

- **Namespaces**: `io.github.bigconfig-ai.walter.*`. Five of them, mapping to distinct concerns — adding a sixth needs a genuinely new concern.
- **The provider registry is Once's.** `validate/providers` is `once.validate/providers` narrowed to Walter's two slots, never a copy. Walter renders Once's OpenTofu templates, so a required key added upstream has to reach Walter's validation automatically; a copy is how a package ends up validating against one set of keys and rendering from another.
- **Keys**: plain kebab-case keywords for desired state; namespaced keywords for engine state (`:green/…`, `:once/…`, `:walter/…`).
- **Steps** take `opts` and return `opts`, and report failure through `:green/exit` / `:green/err`.
- **Reuse rather than reimplement.** The compute stage, the local SSH stage and the provider registry are Once's; the YAML emitter, the process helpers and the `GREEN_PAR_*` overlay are green's (`green.yaml`, `green.process`, `green.cli`). Duplicating any of them lets the copies drift.
- **`^:private`** for everything not called from the launcher or the tests. The launcher's own helpers are `defn-`; the workflow steps it exposes are not.
- **Pure builders stay pure**: `tools/data-fn`, `tools/inventory`, `tools/packages`, `tools/repos`, `tools/ssh-config` take data and return data. `describe/describe-report` keeps its single-argument arity (which shells out) separate from the arities that take an injected runner, so report construction stays process-free — preserve that split.
- **Tests avoid processes** by driving the pure builders directly or by scaffolding into a temp directory.

## Git Conventions

Stay on the `green` branch — each language has its own branch in this repository, and this one is the green rewrite. Commit only when explicitly asked. [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `refactor:`, `docs:`, `chore:`, `deps:`, with `!` and a `BREAKING CHANGE:` footer when desired state or the contract changes.

## What to Avoid

- Do not reintroduce BigConfig SDK concepts: `bb run package …`, `::workflow/params`, `BC_PAR_*`, `options.clj` profile maps.
- Do not add error handling for cases that cannot happen — failure travels through `:green/exit` and `:green/err`, and `green.workflow` converts thrown exceptions itself.
- Do not edit `.green/` (or any configured `:workdir`) — it is generated output.
- Do not put credentials, tokens, or private keys in source, in `green.edn`, or in a rendered file. `.envrc.private` is the local channel.
- Do not hardcode the workstation in `tools.clj`; users, repos and packages belong in `green.edn`.
- Do not give the launcher a dependency outside `green`, `once`, `walter`, and Babashka's built-ins: it has to work as a lone file copied into a stranger's project.
- Do not put logic back in the launcher. Steps, validation, the graph and backends belong in the library, where tests reach them; the launcher resolves the library and dispatches, nothing more.
- Do not copy Once's provider tables into `validate.clj`; narrow the registry instead.
- Do not hand-edit `walter-sha` / `once-sha` / `green-sha`; run `bb pin`.
- When desired state changes, update all five surfaces that document it: `green.edn`, `green-walter/references/configuration.md`, `green-walter/SKILL.md`, `index.html`, and `README.md`.
