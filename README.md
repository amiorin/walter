# Walter (TypeScript)

Walter provisions cloud instances (or targets an existing host) and configures them as a personalized development environment. This is the TypeScript implementation of the Walter package.

## Usage

```bash
npm install
npm run walter -- package validate
npm run walter -- package build
npm run build
```

The root `run` script is the launcher-friendly entry point:

```bash
node run package build
```

Individual tools:

```bash
npm run walter -- tofu render
npm run walter -- ansible render
npm run walter -- ansible-local render
```

Override params with `BC_PAR_*`, for example:

```bash
export BC_PAR_PROVIDER_COMPUTE=no-infra
export BC_PAR_COMPUTE_PUBKEY="$(cat ~/.ssh/id_ed25519.pub)"
export BC_PAR_NO_INFRA_COMPUTE_IP=203.0.113.10
```

`.dist/` is generated output. Do not run provisioning/destructive commands without real credentials and explicit intent.
