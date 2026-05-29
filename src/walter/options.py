"""Walter profiles and the active profile."""
from __future__ import annotations

from typing import Any

from big_config.core import Opts

from .interop import PARAMS, PROFILE, sync_aliases

Profile = dict[str, Any]


def compose(*layers: Profile) -> Opts:
    """Compose layers like Clojure's ``merge-with merge``: params merge shallowly."""
    params: dict[str, Any] = {}
    profile: str | None = None
    for layer in layers:
        if "profile" in layer:
            profile = layer["profile"]
        params = {**params, **(layer.get("params") or {})}
    return sync_aliases({PROFILE: profile, PARAMS: params})


base: Profile = {
    "profile": "walter",
    "params": {
        "package": "walter",
        "compute-pubkey": "REPLACE_ME",
        "provider-compute": "hcloud",
        "provider-backend": "r2",
        "compute-prevent-destroy": True,
        "s3-bucket": "REPLACE_ME",
        "s3-region": "REPLACE_ME",
        "r2-bucket": "REPLACE_ME",
        "r2-endpoint": "REPLACE_ME",
        "r2-access-key-id": "REPLACE_ME",
        "r2-secret-access-key": "REPLACE_ME",
        "oci-config-file-profile": "REPLACE_ME",
        "oci-subnet-id": "REPLACE_ME",
        "oci-compartment-id": "REPLACE_ME",
        "oci-availability-domain": "REPLACE_ME",
        "oci-display-name": "walter",
        "oci-shape": "VM.Standard.A1.Flex",
        "oci-ocpus": 1,
        "oci-memory-in-gbs": 4,
        "oci-boot-volume-size-in-gbs": 50,
        "oci-boot-volume-vpus-per-gb": 30,
        "oci-ssh-authorized-keys": "REPLACE_ME",
        "hcloud-name": "walter",
        "hcloud-image": "ubuntu-24.04",
        "hcloud-server-type": "cx23",
        "hcloud-location": "hel1",
        "hcloud-ssh-keys": "REPLACE_ME",
        "hcloud-token": "REPLACE_ME",
        "digitalocean-name": "walter",
        "digitalocean-region": "ams3",
        "digitalocean-size": "s-1vcpu-1gb-35gb-intel",
        "digitalocean-image": "ubuntu-25-10-x64",
        "digitalocean-vpc-uuid": "REPLACE_ME",
        "digitalocean-ssh-keys": "REPLACE_ME",
        "do-token": "REPLACE_ME",
        "no-infra-compute-ip": "REPLACE_ME",
        "no-infra-compute-user": "ubuntu",
        "no-infra-compute-sudoer": "root",
        "no-infra-compute-uid": "1000",
    },
}

walter: Opts = compose(base)
bb: Opts = walter
