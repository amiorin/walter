{ pkgs, lib, config, inputs, ... }:

{
  languages.ansible.enable = true;
  languages.opentofu.enable = true;
  languages.python = {
    enable = true;
    uv.enable = true;
  };
  packages = [
    pkgs.babashka
    pkgs.jet
    pkgs.hcl2json
    pkgs.awscli2
    pkgs.hcloud
    pkgs.doctl
    pkgs.oci-cli
  ];
}
