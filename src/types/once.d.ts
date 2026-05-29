declare module "once/dist/src/once/tools.js" {
  export const TOFU: string;
  export const ANSIBLE_LOCAL: string;
  export const stepFns: any[];
  export function tofu(sfns: any[], opts: Record<string, any>): Record<string, any>;
  export function ansibleLocal(sfns: any[], opts: Record<string, any>): Record<string, any>;
  export function tofuStar(args: string | string[], opts?: Record<string, any>): Record<string, any>;
  export function ansibleLocalStar(args: string | string[], opts?: Record<string, any>): Record<string, any>;
}

declare module "once/dist/src/once/params.js" {
  export function tofuParams(opts: Record<string, any>): Record<string, any>;
}
