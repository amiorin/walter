#!/usr/bin/env node
/** Command-line entry point. */
import { realpathSync } from "node:fs";
import { resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { Opts } from "big-config";
import * as onceTools from "once/dist/src/once/tools.js";
import { bb } from "./walter/options.js";
import { walterOpts } from "./walter/params.js";
import { walterStar } from "./walter/package.js";
import { ansibleStar } from "./walter/tools.js";

const HELP = `Usage: walter <command> [args...]

Commands:
  package <step>...       Run Walter package workflow steps for the active profile.
                            walter package validate
                            walter package describe
                            walter package build
                            walter package create
                            walter package delete
                            walter package git-check lock build unlock-any

  Package steps:
    validate              Pre-flight profile, tool, credential, and Ansible-data checks.
    describe              Providers, compute reachability, and workstation summary report.
    build                 Render Walter stages without applying/provisioning.
    create                Provision and configure the Walter workstation.
    delete                Destroy the compute Tofu stage.
    lock                  Acquire the BigConfig Git-tag lock.
    git-check             Verify the Git working tree/upstream state is clean.
    git-push              Run git push through the BigConfig workflow.
    unlock-any            Force-release the computed BigConfig lock tag.

  Individual tools (accept SDK workflow steps and exec commands):
  tofu <args>             e.g. walter tofu render tofu:init tofu:apply:-auto-approve
                          e.g. walter tofu git-check lock render tofu:init tofu:plan unlock-any
  ansible <args>          e.g. walter ansible render -- ansible-playbook main.yml
  ansible-local <args>    e.g. walter ansible-local render -- ansible-playbook main.yml

Notes:
  * When launched through \`run\`, the active profile comes from that script;
    otherwise it defaults to \`bb\` in src/walter/options.ts.
  * Any param can be overridden with BC_PAR_* environment variables.`;

const PACKAGE_COMMANDS = new Set(["validate", "describe", "build", "create", "delete", "lock", "git-check", "git-push", "unlock-any"]);

function die(...lines: string[]): never {
  for (const line of lines) console.error(line);
  process.exit(1);
}

export function main(argv0: string[], opts: Opts = bb): void {
  const argv = argv0[0] === "--" ? argv0.slice(1) : argv0;
  const [command, ...rest] = argv;
  switch (command) {
    case undefined:
    case "help":
    case "--help":
    case "-h":
      console.log(HELP);
      return;
    case "package":
      if (["help", "--help", "-h"].includes(rest[0] ?? "")) {
        console.log(HELP);
        return;
      }
      if (rest.length === 0) die("Missing package step.", "Usage: walter package <step>...");
      walterStar(rest, opts);
      return;
    case "tofu":
      onceTools.tofuStar(rest, walterOpts(opts));
      return;
    case "ansible":
      ansibleStar(rest, walterOpts(opts));
      return;
    case "ansible-local":
      onceTools.ansibleLocalStar(rest, walterOpts(opts));
      return;
    default:
      if (PACKAGE_COMMANDS.has(command)) die(`Use \`walter package ${command}\`.`, "", HELP);
      die(`Unknown command: ${command}`, "", HELP);
  }
}

function isMainModule(): boolean {
  const entry = process.argv[1];
  if (!entry) return false;
  const modulePath = fileURLToPath(import.meta.url);
  try {
    return realpathSync(entry) === realpathSync(modulePath);
  } catch {
    return resolve(entry) === modulePath;
  }
}

if (isMainModule()) {
  main(process.argv.slice(2));
}
