/** Walter parameter composition. */
import type { Opts } from "big-config";
import * as bcWorkflow from "big-config/workflow";
import { tofuParams } from "once/dist/src/once/params.js";
import { syncAliases, toBcOpts } from "./interop.js";

const START_STEP = "io.github.bigconfig-ai.walter.package/start-create-or-delete";

export function optsFn(opts: Opts): Opts {
  const withEnv = syncAliases(bcWorkflow.readBcPars(toBcOpts(opts)));
  return syncAliases(tofuParams(withEnv));
}

export function walterOpts(opts: Opts): Opts {
  return optsFn(bcWorkflow.newPrefix(toBcOpts(opts), START_STEP));
}
