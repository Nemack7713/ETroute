"""Provider-neutral cloud readiness stubs for ETroute.

No provider SDKs or external network calls are performed by this module.
"""
from __future__ import annotations

from dataclasses import asdict, dataclass


@dataclass(frozen=True)
class CloudHealth:
    provider: str
    configured: bool
    ready: bool
    mode: str = "stub"
    message: str = "provider adapter not configured"

    def as_dict(self) -> dict[str, object]:
        return asdict(self)


class CloudProvider:
    name = "unknown"

    def health(self) -> CloudHealth:
        return CloudHealth(self.name, configured=False, ready=False)


class AWSProvider(CloudProvider):
    name = "aws"


class GCPProvider(CloudProvider):
    name = "gcp"


class AzureProvider(CloudProvider):
    name = "azure"


def provider(name: str) -> CloudProvider:
    providers = {item.name: item for item in (AWSProvider(), GCPProvider(), AzureProvider())}
    try:
        return providers[name.lower()]
    except KeyError as exc:
        raise ValueError(f"unsupported cloud provider: {name}") from exc
