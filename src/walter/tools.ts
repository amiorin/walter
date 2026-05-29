/** Walter-owned tool workflows. */
import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import {
  ENV,
  RENDER_TEMPLATES,
  WF_NAME,
  type Opts,
  type StepFn,
} from "big-config";
import { createExitStepFn, createPrintErrorStepFn } from "big-config/step-fns";
import { keywordToPath } from "big-config/utils";
import { parseArgs, prepare, printStepFn, runSteps } from "big-config/workflow";
import { dataFn, render } from "./ansible.js";

const END = "big-config.workflow/end";

export const stepFns: StepFn[] = [
  printStepFn,
  createExitStepFn(END),
  createPrintErrorStepFn(END),
];

export const delimiters = {
  "tag-open": "<",
  "tag-close": ">",
  "filter-open": "{",
  "filter-close": "}",
};

export const ANSIBLE = "io.github.bigconfig-ai.walter.tools/ansible";

const HERE = dirname(fileURLToPath(import.meta.url));

function templatePath(template: string): string {
  const rel = keywordToPath(template);
  const candidates = [
    join(HERE, "..", "resources", rel),
    join(HERE, "..", "..", "resources", rel),
    join(HERE, "..", "..", "..", "src", "resources", rel),
  ];
  return candidates.find((candidate) => existsSync(candidate)) ?? rel;
}

export function ansible(sfns: StepFn[], opts: Opts): Opts {
  const prepared = prepare(
    {
      [WF_NAME]: ANSIBLE,
      [RENDER_TEMPLATES]: [
        {
          template: templatePath(ANSIBLE),
          overwrite: true,
          "data-fn": dataFn,
          transform: [
            [".", "raw"],
            [render, "roles/users/tasks", { packages: "packages.yml", repos: "repos.yml", "ssh-config": "ssh-config.yml" }, "raw"],
            [render, { inventory: "inventory.json", config: "default.config.yml" }, "raw"],
          ],
        },
      ],
    },
    opts,
  );
  return runSteps(sfns, prepared);
}

export function ansibleStar(args: string | string[], opts: Opts = {}): Opts {
  return ansible(stepFns, { ...parseArgs(args), [ENV]: "shell", ...opts });
}
