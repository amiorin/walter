{ pkgs, lib, config, inputs, ... }:

{
  languages.javascript = {
    enable = true;
    npm.enable = true;
  };
  languages.ansible.enable = true;
  languages.opentofu.enable = true;
  packages = [
    pkgs.nodejs_22
    pkgs.babashka
    pkgs.jet
    pkgs.hcl2json
    pkgs.awscli2
    pkgs.hcloud
    pkgs.doctl
    pkgs.oci-cli
  ];
}
