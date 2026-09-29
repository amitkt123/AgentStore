from __future__ import annotations

from collections.abc import Mapping
from typing import Any, TypeVar

from attrs import define as _attrs_define
from attrs import field as _attrs_field
from typing_extensions import Self

from ..types import UNSET, Unset

T = TypeVar("T", bound="NetworkEvent")


@_attrs_define
class NetworkEvent:
    """
    Attributes:
        host (str):
        count (int | Unset):
        callsite (str | Unset):
    """

    host: str
    count: int | Unset = UNSET
    callsite: str | Unset = UNSET
    additional_properties: dict[str, Any] = _attrs_field(init=False, factory=dict)

    def to_dict(self) -> dict[str, Any]:
        host = self.host

        count = self.count

        callsite = self.callsite

        field_dict: dict[str, Any] = {}
        field_dict.update(self.additional_properties)
        field_dict.update(
            {
                "host": host,
            }
        )
        if count is not UNSET:
            field_dict["count"] = count
        if callsite is not UNSET:
            field_dict["callsite"] = callsite

        return field_dict

    @classmethod
    def from_dict(cls, src_dict: Mapping[str, Any]) -> Self:
        d = dict(src_dict)
        host = d.pop("host")

        count = d.pop("count", UNSET)

        callsite = d.pop("callsite", UNSET)

        network_event = cls(
            host=host,
            count=count,
            callsite=callsite,
        )

        network_event.additional_properties = d
        return network_event

    @property
    def additional_keys(self) -> list[str]:
        return list(self.additional_properties.keys())

    def __getitem__(self, key: str) -> Any:
        return self.additional_properties[key]

    def __setitem__(self, key: str, value: Any) -> None:
        self.additional_properties[key] = value

    def __delitem__(self, key: str) -> None:
        del self.additional_properties[key]

    def __contains__(self, key: str) -> bool:
        return key in self.additional_properties
