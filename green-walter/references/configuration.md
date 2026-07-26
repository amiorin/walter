# Configuration reference

The generated `green.edn` is one root EDN map with flat provider and setting keys, except for the workstation nested under `:walter {...}`. Include only selected providers' non-secret settings. Never add credentials or passwords.

Launcher-managed provider credentials reach the workflow through `GREEN_PAR_*` environment variables, which are overlaid onto matching flat keys before anything runs. A variable name is the key uppercased with hyphens as underscores, so `:hcloud-token` is supplied by `GREEN_PAR_HCLOUD_TOKEN`; there is no `TF_VAR_*` alias. S3 is the exception: OpenTofu resolves its ambient AWS credential chain directly. OCI authenticates through the selected profile in `~/.oci/config`, and SSH private keys remain outside the project in `ssh-agent`. From there no secret is written into a rendered file: OpenTofu credentials are passed to the compute stage under the variable that provider reads natively. Overrides are coerced to the type of the value they replace, so booleans and integers stay booleans and integers.

## Base shape

```clojure
{:profile "walter"
 :workdir ".green"

 :compute-pubkey "ssh-ed25519 AAAA... me@example.com"

 :walter {:tailnet "example-tailnet.ts.net"
          :users [{:name "ubuntu"
                   :doomemacs "dd72eac1971616a6ebe81067cca33b14c148cbcd"
                   :remove false}]
          :repos [{:user "ubuntu" :org "acme" :repo "site" :branch "main" :worktrees []}]
          :packages [["ripgrep" "rg"]
                     ["fish" "fish"]]}

 :provider-compute "oci"
 :provider-backend "s3"
 :compute-prevent-destroy true

 ;; Add the selected providers' non-secret fields here.
 }
```

`:profile` names the stack: it is the working-directory and state-key prefix, the `name` the compute stage reports, and the `Host` alias written into the local `~/.ssh/config`.

`:compute-pubkey` is required. It is authorized for every user the play creates on the workstation. When a cloud compute provider builds the box, the matching private key must be loaded in `ssh-agent` — it never belongs in the project.

## The workstation (`:walter`)

Everything the Ansible stage builds is desired state. Nothing is hardcoded in the source.

### `:tailnet`

The Tailscale tailnet domain. The play writes a `Host <ip>` block into the remote user's `~/.ssh/config` pointing at `<ip>.<tailnet>`, so the workstation can reach itself by name over the tailnet.

### `:users`

```clojure
:users [{:name "ubuntu" :doomemacs "<commit-sha>" :remove false}]
```

- `:name` — the Unix account the play creates. At least one user is required.
- `:uid` — optional. A user that names no `:uid` inherits the uid the image's default user already has, reported by the compute stage. The play creates the account *by uid*, so a clash would fail; name a `:uid` only when you want to override that.
- `:doomemacs` — the Doom Emacs commit the play pins for this user.
- `:remove true` — deletes the account and its home directory instead of creating it.

### `:repos`

```clojure
:repos [{:user "ubuntu" :org "acme" :repo "site" :branch "main" :worktrees ["staging"]}]
```

Each entry is cloned into `code/personal/<repo>/<branch>` over SSH, and each name in `:worktrees` is added as a sibling git worktree. `:user` gates the task to that user's inventory host, so several users can be provisioned with different repositories.

### `:packages`

```clojure
:packages [["ripgrep" "rg"] ["fish" "fish"]]
```

Each entry is `[devbox-package cli]`. The play installs the package with `devbox global add` and checks for `cli` on PATH to decide whether the work is already done — so a package whose binary is named differently needs both, as `ripgrep`/`rg` does. When they match, repeat the name.

## Compute providers

### Oracle Cloud Infrastructure

```clojure
:provider-compute "oci"
:oci-config-file-profile "DEFAULT"
:oci-subnet-id "ocid1.subnet.oc1..."
:oci-compartment-id "ocid1.tenancy.oc1..."
:oci-availability-domain "xTQn:EU-FRANKFURT-1-AD-1"
:oci-display-name "walter"
:oci-shape "VM.Standard.A1.Flex"
:oci-ocpus 1
:oci-memory-in-gbs 6
:oci-boot-volume-size-in-gbs 50
:oci-boot-volume-vpus-per-gb 30
:oci-ssh-authorized-keys "~/.ssh/id_ed25519.pub"
```

No `GREEN_PAR_*` credential: OCI authenticates through the named profile in `~/.oci/config`. `:oci-ssh-authorized-keys` is a *path* to a public-key file that OpenTofu reads at plan time on the machine running the launcher — record the path, never the file's contents.

### Hetzner Cloud

```clojure
:provider-compute "hcloud"
:hcloud-name "walter"
:hcloud-image "ubuntu-24.04"
:hcloud-server-type "cx23"
:hcloud-location "hel1"
:hcloud-ssh-keys "name-or-id-already-in-the-account"
```

Required credential: `GREEN_PAR_HCLOUD_TOKEN`.

### DigitalOcean

```clojure
:provider-compute "digitalocean"
:digitalocean-name "walter"
:digitalocean-region "ams3"
:digitalocean-size "s-1vcpu-1gb-35gb-intel"
:digitalocean-image "ubuntu-25-10-x64"
:digitalocean-ssh-keys "fingerprint-or-id-already-in-the-account"
;; Optional:
:digitalocean-vpc-uuid "non-secret-vpc-uuid"
```

Required credential: `GREEN_PAR_DO_TOKEN`.

### No infrastructure

```clojure
:provider-compute "no-infra"
:no-infra-compute-ip "203.0.113.10"
:no-infra-compute-user "ubuntu"
:no-infra-compute-sudoer "root"
:no-infra-compute-uid "1000"
```

Targets a host that already exists. OpenTofu creates nothing, so the compute stage is never reported as `absent` — only `running` or `unreachable`. No credential is required.

## State backends

### S3

```clojure
:provider-backend "s3"
:s3-bucket "tf-state-example"
:s3-region "eu-west-1"
```

No `GREEN_PAR_*` credential: OpenTofu uses the ambient AWS credential chain.

### Cloudflare R2

```clojure
:provider-backend "r2"
:r2-bucket "tf-state"
:r2-endpoint "https://<account>.r2.cloudflarestorage.com"
```

Required credentials: `GREEN_PAR_R2_ACCESS_KEY_ID` and `GREEN_PAR_R2_SECRET_ACCESS_KEY`. They are passed as `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` rather than written into `backend.tf.json`, which would also copy them into `.terraform/terraform.tfstate`.

### Local

```clojure
:provider-backend "local"
```

State stays in the work directory. No credential.

## Safety

`:compute-prevent-destroy` defaults to `true` and renders `lifecycle { prevent_destroy = true }` on the compute resource. A real `delete` refuses to start until `GREEN_PAR_COMPUTE_PREVENT_DESTROY=false` is set in the environment — authorize an intentional teardown that way rather than by editing committed desired state.

## Overriding anything

Every flat key can be overridden by its `GREEN_PAR_*` variable, not just credentials:

```sh
GREEN_PAR_PROFILE=laptop ./green build
GREEN_PAR_PROVIDER_COMPUTE=no-infra GREEN_PAR_NO_INFRA_COMPUTE_IP=203.0.113.10 ./green build
```

Nested keys under `:walter` cannot be overridden this way — edit the file, or keep a second desired-state file and select it with `-f`.
