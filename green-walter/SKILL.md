---
name: green-walter
description: Creates and operates cloud development workstations with Green, OpenTofu, and Ansible. Use when initializing a Walter project, generating green.edn, selecting cloud/state providers, choosing the users, repositories and packages to provision, building or dry-running configuration, provisioning, deleting, or describing a workstation.
license: MIT
---

# Development workstations with Green

Use this skill to initialize or operate a Walter workstation in the user's current directory.

## Requirements

Babashka runs the launcher. `create` and `delete` also require OpenTofu and Ansible. `describe` requires OpenTofu and OpenSSH locally. Provider credentials arrive as `GREEN_PAR_*` variables, except OCI, which uses the profile named in `~/.oci/config`, and S3, which uses OpenTofu's ambient AWS credential chain.

## Non-negotiable safety rules

- Never ask the user to paste a secret into chat.
- Never put API tokens, passwords, private keys, or access keys in `green.edn`, `green`, shell history, logs, or generated examples. Provider credentials use a `GREEN_PAR_*` environment variable named after the key it fills. S3 uses OpenTofu's ambient AWS credential chain, OCI uses the configured profile in `~/.oci/config`, and SSH private keys remain in `ssh-agent`; never copy those credentials into project files.
- Suggest the user keep `GREEN_PAR_*` exports in a gitignored file such as `.envrc.private`, never inline in a command their shell history records.
- Public SSH keys are not secrets. Read only a user-approved `.pub` file; never read a private SSH key. `:oci-ssh-authorized-keys` is the exception to reading at all: it holds a *path* to a public-key file that OpenTofu reads at plan time on the machine running the launcher, so record the path and never inline the file's contents.
- Do not overwrite an existing `green` or `green.edn` without explicit approval. If an existing project is valid, operate it instead of regenerating it.
- If the launcher reports a contract mismatch, its pinned commit is older than the launcher itself. Re-copy `green` from an updated skill; there is no command in the project that fixes it.
- Default to `build` and `create --dry-run`. Run a real `create` or `delete` only after the user explicitly confirms that exact operation.
- `build` and `create --dry-run` are credential-free by design and check no `GREEN_PAR_*` at all. A clean dry-run says nothing about whether real provisioning would authenticate; never report it as credential validation.
- Before delete, remind the user that `:compute-prevent-destroy` defaults to `true`. Authorize an intentional delete with `GREEN_PAR_COMPUTE_PREVENT_DESTROY=false` in the environment rather than by editing committed desired state.
- A workstation is a long-lived machine holding the user's own repositories and shell history. Never suggest `delete` as a way to fix a failed `create` — rerun `create`, which is idempotent.

Read [references/configuration.md](references/configuration.md) before generating or changing desired state, and before any real `create` or `delete`.

## Initialize in the current directory

Determine this skill's directory from the loaded `SKILL.md` path. Do not assume the skill directory is the current working directory.

Gather these non-secret inputs conversationally:

- profile name and working directory (default `.green`)
- compute and backend providers, and the selected providers' non-secret settings
- `:compute-pubkey`, which is always required: the SSH public key authorized for every user the play creates
- the workstation itself, under `:walter`:
  - `:tailnet` — the Tailscale tailnet domain the box reaches itself by
  - `:users` — at least one; ask for the account name and, only if they want to override the image default, a uid
  - `:repos` — owner, repository, branch, and any extra git worktrees
  - `:packages` — devbox packages as `[package cli]` pairs; ask for the CLI name only when it differs from the package name

Do not request secret values. Tell the user which `GREEN_PAR_*` names and native credential mechanisms are required for their selected providers.

After confirming the inputs:

- Copy the bundled `green` file from this skill directory to `./green` and make it executable.
- Write `./green.edn` following the reference: keep provider and setting keys in the root map, and nest the workstation exactly under `:walter {...}`. Omit all secret keys and values.
- Ensure the configured work directory is ignored by Git, and that any file holding `GREEN_PAR_*` exports is too. Append precise ignore entries without replacing unrelated `.gitignore` content.
- Verify that `green.edn` contains no credential, password, access-key, or private-key fields. Do not read environment-variable values; verify presence only. Confirm that `green` is an exact copy of the bundled launcher.
- Run `./green build -f ./green.edn`.
- Run `./green create -f ./green.edn --dry-run`.
- Report generated paths and required environment-variable names, but never their values. State plainly that neither check validated credentials, and list which `GREEN_PAR_*` names a real `create` will require.

If verification fails, correct only `green.edn`, the ignore entries, or the copied `green` launcher as appropriate, then rerun the safe checks. Never edit the configured work directory; it is generated output. Do not proceed to real provisioning automatically.

## Operate an existing project

Read `green.edn` first and identify its providers and work directory. Use:

```sh
./green build
./green create --dry-run
./green create
./green describe
./green delete --dry-run
./green delete
```

Every command reads `./green.edn` unless `-f|--file` names another desired-state file, which is how one project holds several workstations (`./green build -f laptop.edn`).

`build` renders OpenTofu and Ansible configuration without invoking them. Dry-run touches nothing. `describe` reads the OpenTofu outputs already in the work directory, probes SSH, and summarizes the workstation desired state would build. Compute is reported as `running`, `unreachable` (state holds an address but SSH failed), or `absent` (the compute stage has no outputs, so it was never created); `no-infra` hosts are never `absent`, since OpenTofu does not create them. Describe exits non-zero when compute is not `running`.

`create` is idempotent: every Ansible task is guarded by a `creates:` check, so rerunning it after a partial failure resumes rather than repeats.

Before real create/delete, check required `GREEN_PAR_*` variables by presence only. Do not print them. For OCI, S3, and SSH, confirm that the selected native credential mechanism is configured without reading secret material. Let the launcher perform its own final desired-state and environment validation.

## Changing the workstation

Users, repositories and packages are desired state, not source — edit `green.edn` and rerun `create`. Two rules are easy to get wrong:

- **`:packages` entries are `[package cli]`.** The play checks for the CLI on PATH to decide whether the package is installed. Getting the second element wrong makes the task rerun on every play.
- **`:users` entries are created by uid.** A user that names no `:uid` inherits the uid the image's default user already has, which is what you want on OCI. Adding an explicit `:uid` that clashes with an existing account fails the play.

Removing a user from `:users` does not delete their account. Set `:remove true` on the entry instead, which deletes the account and its home directory, and drop the entry on a later run.
