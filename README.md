# Walter

Walter provisions a cloud VM (or targets an existing host) and configures it as a personalized development workstation. It combines **OpenTofu** for infrastructure and **Ansible** for configuration, orchestrated through **Clojure** and **Babashka** on top of [`green`](https://github.com/amiorin/green), a DAG workflow engine.

The compute stage and the local `~/.ssh/config` stage are shared with [`once`](https://github.com/bigconfig-ai/once); Walter owns the Ansible run that builds the workstation.

## Features

- Provision compute on OCI, Hetzner Cloud, or DigitalOcean — or target an existing host with `no-infra`.
- Configure users, SSH, shell tooling, and system services through Walter's Ansible roles.
- Install development tools through `devbox`, languages through `asdf`, and Doom Emacs pinned to a commit.
- Clone project repositories and their git worktrees.
- Keep the whole workstation — users, repos, packages, tailnet — in one `green.edn` file.

## Prerequisites

- [Babashka](https://babashka.org/) — runs the launcher
- [OpenTofu](https://opentofu.org/) and [Ansible](https://www.ansible.com/) — for `create` and `delete`
- OpenSSH — for `describe`
- Cloud credentials for the selected provider, or existing host details for `no-infra`

## Usage

```bash
./green build              # render <workdir>/<profile>/ only, no tofu, no ansible
./green create --dry-run   # print the DAG actions, touch nothing
./green create             # provision and configure
./green describe           # providers, compute status, workstation summary
./green delete             # destroy the compute stage
```

Every command reads `./green.edn` unless `-f|--file` names another file, which is how one project holds several workstations:

```bash
./green build -f laptop.edn
```

Inside this repository, run the launcher through Babashka so `bb.edn`'s local roots apply:

```bash
bb green build
```

## Configuration

Desired state lives in `green.edn` — one flat EDN map, with the workstation nested under `:walter`:

```clojure
{:profile "walter"
 :workdir ".green"
 :compute-pubkey "ssh-ed25519 AAAA... me@example.com"
 :walter {:tailnet "example-tailnet.ts.net"
          :users [{:name "ubuntu" :doomemacs "<commit-sha>" :remove false}]
          :repos [{:user "ubuntu" :org "acme" :repo "site" :branch "main" :worktrees []}]
          :packages [["ripgrep" "rg"] ["fish" "fish"]]}
 :provider-compute "oci"
 :provider-backend "s3"
 :compute-prevent-destroy true}
```

Users, repositories and packages are configuration, not source: change them here and rerun `create`, which is idempotent.

See [green-walter/references/configuration.md](green-walter/references/configuration.md) for every key, per provider.

### Secrets

Credentials are never written to `green.edn`. Each one is supplied at runtime by a `GREEN_PAR_*` environment variable named after the key it fills — uppercased, hyphens as underscores:

```bash
export GREEN_PAR_HCLOUD_TOKEN=...        # Hetzner Cloud
export GREEN_PAR_DO_TOKEN=...            # DigitalOcean
export GREEN_PAR_R2_ACCESS_KEY_ID=...    # R2 state backend
export GREEN_PAR_R2_SECRET_ACCESS_KEY=...
```

OCI authenticates through the profile named in `~/.oci/config`, and S3 through OpenTofu's ambient AWS credential chain. Keep these exports in a gitignored file such as `.envrc.private`.

Any flat key can be overridden the same way, not just credentials:

```bash
GREEN_PAR_PROVIDER_COMPUTE=no-infra GREEN_PAR_NO_INFRA_COMPUTE_IP=203.0.113.10 ./green build
```

### Deleting

`:compute-prevent-destroy` defaults to `true`. A real `delete` refuses to start until you authorize it in the environment:

```bash
GREEN_PAR_COMPUTE_PREVENT_DESTROY=false ./green delete
```

## Development

```bash
clojure -M:test                # run the test suite
clojure-lsp clean-ns && clojure-lsp format
bb pin                         # restamp the launcher's pins (maintainers only)
```

`bb.edn` overrides the pinned `green` and `once` dependencies with local roots, so changes to either are picked up without publishing.

The launcher carries no logic — validation, the workflow graph and the steps live in the library, under test. `bb pin` is a maintainer task rather than a launcher subcommand: it reads the HEAD of the checkout it runs in, so shipping it inside the payload would stamp an unrelated SHA in someone else's project.

`.green/` (or whatever `:workdir` names) is generated output and should not be edited directly.

## Skill

`green-walter/` is an agent skill whose payload is the launcher itself. Copied into another project, `green` resolves `walter`, `once` and `green` as pinned git dependencies and needs nothing else installed.

## License

Copyright © 2026 Alberto Miorin.

Distributed under the MIT License.
