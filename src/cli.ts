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
  package <step>...       Validate, build, provision, or tear down Walter infrastructure.
                            walter package validate
                            walter package build
                            walter package create
                            walter package delete

  Individual tools (each requires \`render\` first):
  tofu <args>             e.g. walter tofu render tofu:init tofu:apply:-auto-approve
  ansible <args>          e.g. walter ansible render -- ansible-playbook main.yml
  ansible-local <args>    e.g. walter ansible-local render -- ansible-playbook main.yml

Notes:
  * When launched through \`run\`, the active profile comes from that script;
    otherwise it defaults to \`bb\` in src/walter/options.ts.
  * Any param can be overridden with BC_PAR_* environment variables.`;

const PACKAGE_COMMANDS = new Set(["validate", "build", "create", "delete"]);

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
      if (rest.length === 0) die("Missing package step.", "Usage: walter package <validate|build|create|delete>...");
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
