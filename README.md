# Walter

Walter is an infrastructure automation tool designed to provision cloud instances and configure them as a personalized development environment. It combines the power of **OpenTofu** (for infrastructure) and **Ansible** (for configuration), all orchestrated through **Clojure** and **Babashka**.

The primary goal of Walter is to automate the setup of a consistent, high-productivity development environment on cloud providers like **Hetzner Cloud (hcloud)** and **Oracle Cloud Infrastructure (OCI)**.

## Features

- **Infrastructure as Code (IaC):** Provision cloud resources using OpenTofu templates.
- **Automated Configuration:** Set up users, SSH keys, and system settings using Ansible roles.
- **Developer Tooling:** Automatically install common development tools via `devbox` (Nix-based package manager).
- **Project Setup:** Clone your Git repositories and configure multiple worktrees automatically.
- **Personalized Environment:** Built-in support for tools like Emacs (Doom Emacs), Zellij, Fish, Atuin, and more.
- **Orchestrated Workflow:** Single-command execution to go from nothing to a fully working cloud development box.

## Prerequisites

- [Babashka](https://babashka.org/)
- [Clojure](https://clojure.org/)
- [OpenTofu](https://opentofu.org/)
- [Ansible](https://www.ansible.com/)
- [Devbox](https://github.com/jetpack-io/devbox) (used on the target instance)
- [devenv](https://devenv.sh/) (optional, for setting up the local development environment)

## Getting Started

### 1. Configuration

Walter uses [big-config](https://github.com/amiorin/big-config) for managing its configuration and templates. 

You may need to set environment variables or provide configuration values for your hyperscalers:
- `HCLOUD_TOKEN` for Hetzner Cloud.
- OCI configuration (e.g., via `oci-cli`) for Oracle Cloud.

### 2. Available Commands

Walter uses Babashka (`bb`) to expose its functionality:

- **Full Workflow:**
  ```bash
  bb walter create
  ```
  This command performs a full "create" cycle: it renders OpenTofu templates, initializes and applies the infrastructure, then renders Ansible playbooks and executes them against the newly created instance.

- **OpenTofu Tasks:**
  ```bash
  bb tofu render          # Render templates to .dist/
  bb tofu init            # Initialize OpenTofu
  bb tofu plan            # Preview changes
  bb tofu apply           # Apply infrastructure changes
  bb tofu destroy         # Teardown infrastructure
  ```

- **Ansible Tasks:**
  ```bash
  bb ansible render       # Render Ansible playbooks and inventories
  bb ansible playbook     # Run the Ansible playbook
  bb ansible-local        # Run Ansible tasks locally
  ```

### 3. Customization

The configuration logic is primarily located in:
- `src/io/github/amiorin/walter/ansible.clj`: Defines users, packages, and repositories.
- `resources/io/github/amiorin/walter/tools/`: Contains OpenTofu and Ansible templates.

You can modify `ansible.clj` to change the list of default packages or the repositories you want to clone.

## Project Structure

- `src/`: Clojure source code for orchestration logic.
- `resources/`: OpenTofu and Ansible templates and roles.
- `env/`: Development environment setup.
- `.dist/`: (Generated) Temporary directory for rendered configuration files and tool state.
- `bb.edn`: Task definitions for Babashka.
- `deps.edn`: Clojure dependencies.

## License

Copyright © 2024 Alberto Miorin.

Distributed under the MIT License.
